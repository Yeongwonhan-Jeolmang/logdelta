// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.command;

import io.github.logdelta.cli.CliContext;
import io.github.logdelta.cli.ExitCode;
import io.github.logdelta.cli.args.ArgParser;
import io.github.logdelta.cli.args.OptionSpec;
import io.github.logdelta.cli.args.ParsedArgs;
import io.github.logdelta.cli.args.UsageException;
import io.github.logdelta.cli.render.Ansi;
import io.github.logdelta.cli.render.FolderRenderer;
import io.github.logdelta.cli.render.Glyphs;
import io.github.logdelta.cli.render.SideBySideRenderer;
import io.github.logdelta.cli.render.SummaryRenderer;
import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.engine.LogDeltaEngine;
import io.github.logdelta.core.folder.FolderComparer;
import io.github.logdelta.core.folder.FolderComparison;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.io.LogSources;
import io.github.logdelta.core.report.FolderReports;
import io.github.logdelta.core.report.ReportFormat;
import io.github.logdelta.core.report.ReportOptions;
import io.github.logdelta.core.report.Reports;
import io.github.logdelta.core.session.Session;
import io.github.logdelta.core.session.SessionIO;
import io.github.logdelta.core.session.SessionParseException;
import io.github.logdelta.core.util.CancellationToken;
import io.github.logdelta.core.util.ProgressListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// `logdelta diff`: compares logs (or folders of logs) and prints or writes the result.
public final class DiffCommand implements Command {

    /// Terminal output formats in addition to [ReportFormat].
    private static final String SIDE_BY_SIDE = "side-by-side";
    private static final String SUMMARY = "summary";

    @Override
    public String name() {
        return "diff";
    }

    @Override
    public String summary() {
        return "compare two logs or two folders (default command)";
    }

    @Override
    public String usage() {
        return "[diff] [options] BASELINE... CANDIDATE | BASELINE_DIR CANDIDATE_DIR | --session FILE";
    }

    @Override
    public String details() {
        return """
                Inputs may be files, gzip files, '-' for standard input, or http(s) URLs.
                With more than two inputs, all but the last are known-good baselines: the first
                is shown side by side, and differences any other baseline explains are treated
                as normal variation. Two folders are compared file by file.

                Formats:
                  side-by-side  two columns with inline highlights (default on a terminal)
                  unified       classic unified diff (default when piped)
                  summary       statistics, problems, timing and suggested rules
                  html          self-contained HTML report
                  json          machine-readable report
                  markdown      short summary for pull requests and tickets
                  junit         JUnit XML; each new problem is a failing test
                Folders support summary (a table; the default), markdown, json and junit.

                Exit status: 0 = no differences, 1 = differences, 2 = error.
                With --exit-code problems, 1 is returned only if new warnings/errors appear.

                Structured logs: JSON lines and logfmt are detected automatically and compared
                field by field; timestamps and trace/span ids are ignored by default.
                Known formats (Spring Boot, nginx, Kubernetes, journald, logcat, Python) are
                recognised and their run-specific parts masked; see 'logdelta rules --presets'.

                Reports (html, json, markdown, junit, unified) have secrets and e-mail addresses
                removed; terminal output is left as it is. A .logdelta profile in the current
                folder or a parent folder is used automatically (--no-profile to skip it).

                Examples:
                  logdelta good.log bad.log
                  logdelta -f summary -I heartbeat run1.log.gz run2.log.gz
                  logdelta good-1.log good-2.log good-3.log candidate.log
                  logdelta -p team.ldprofile -f html -o report.html good.log bad.log
                  logdelta --include '*.log' logs/release-41 logs/release-42
                  logdelta -H 'Authorization: Bearer $TOKEN' https://ci.example.com/a.log b.log
                  logdelta --since +2m --until +5m huge-good.log huge-bad.log
                  logdelta --ignore-field requestId good.jsonl bad.jsonl
                """;
    }

    @Override
    public ArgParser parser() {
        ArgParser p = new ArgParser()
                .add(OptionSpec.value("format", 'f', "FMT", "output format (see below)"))
                .add(OptionSpec.value("output", 'o', "FILE", "write to FILE instead of standard output"))
                .add(OptionSpec.repeated("report", 'r', "[FMT:]FILE",
                        "also write a report; format from the extension (.html .json .md .xml .diff)"))
                .add(OptionSpec.value("context", 'C', "N", "unchanged lines around each change (default 3)"))
                .add(OptionSpec.flag("full", '\0', "include all unchanged lines (collapsed in HTML)"))
                .add(OptionSpec.value("max-hunks", '\0', "N", "show at most N change blocks"))
                .add(OptionSpec.repeated("baseline", 'b', "FILE", "another known-good run (same as an extra input)"))
                .add(OptionSpec.value("session", 's', "FILE", "compare the logs saved in a session (.ldsession)"))
                .add(OptionSpec.value("algorithm", 'a', "NAME", "myers (default) or patience"))
                .add(OptionSpec.flag("minimal", '\0', "always compute a minimal diff (slower on huge inputs)"))
                .add(OptionSpec.flag("no-moves", '\0', "do not detect moved lines"))
                .add(OptionSpec.flag("multiline", 'M', "treat a line and its continuation lines (stack traces) as one entry"));
        OptionSupport.addNormalizationOptions(p);
        Inputs.addFolderOptions(p);
        Inputs.addSourceOptions(p);
        return p
                .add(OptionSpec.value("encoding", 'e', "CHARSET", "input encoding (default: auto-detect)"))
                .add(OptionSpec.value("width", '\0', "N", "terminal width for side-by-side (default $COLUMNS or 160)"))
                .add(OptionSpec.value("color", '\0', "WHEN", "auto (default), always or never"))
                .add(OptionSpec.value("exit-code", '\0', "MODE", "any (default), problems or never"))
                .add(OptionSpec.flag("quiet", 'q', "print nothing; only set the exit status"))
                .add(OptionSpec.flag("progress", '\0', "show progress on standard error"))
                .add(OptionSpec.flag("help", 'h', "show this help"));
    }

    @Override
    public int execute(ParsedArgs args, CliContext ctx) throws Exception {
        List<String> inputs = new ArrayList<>(args.positionals());
        DiffOptions base = null;
        if (args.has("session")) {
            if (!inputs.isEmpty()) {
                throw new UsageException("--session cannot be combined with input files");
            }
            Session session = readSession(ctx.resolve(args.get("session")));
            inputs.add(session.left());
            inputs.addAll(session.extraBaselines());
            inputs.add(session.right());
            base = session.options();
        }
        if (inputs.size() < 2) {
            throw new UsageException("Expected a baseline and a candidate, got " + inputs.size() + " input(s)");
        }
        // --baseline adds known-good runs after the main baseline.
        inputs.addAll(inputs.size() - 1, args.all("baseline"));

        DiffOptions options = OptionSupport.buildOptions(args, ctx, base);
        String exitMode = choice(args, "exit-code", "any", "any", "problems", "never");
        String colorMode = choice(args, "color", "auto", "auto", "always", "never");
        ReportOptions reportOptions = new ReportOptions(args.getInt("context", 3), args.has("full"),
                args.getInt("max-hunks", 0));
        List<Outputs.Target> reports = new ArrayList<>();
        for (String r : args.all("report")) {
            reports.add(Outputs.target(r, ctx));
        }
        Charset charset = OptionSupport.charset(args);
        LogSources.RemoteOptions remote = Inputs.remoteOptions(args);
        ProgressListener progress = StderrProgress.of(args.has("progress"), ctx.err());

        Path outFile = args.has("output") ? ctx.resolve(args.get("output")) : null;
        boolean color = outFile == null && ctx.useColor(colorMode);
        int width = args.getInt("width", ctx.terminalWidth(160));
        Glyphs glyphs = Glyphs.of(outFile != null || ctx.unicode());

        boolean leftDir = Inputs.isDirectory(ctx, inputs.getFirst());
        boolean rightDir = Inputs.isDirectory(ctx, inputs.getLast());
        if (leftDir || rightDir) {
            if (!leftDir || !rightDir) {
                throw new UsageException("Compare a folder with a folder, or a file with a file");
            }
            List<Path> extraDirs = new ArrayList<>();
            for (String extra : inputs.subList(1, inputs.size() - 1)) {
                if (!Inputs.isDirectory(ctx, extra)) {
                    throw new UsageException("Extra baselines for a folder comparison must be folders: " + extra);
                }
                extraDirs.add(ctx.resolve(extra));
            }
            FolderComparison c = new FolderComparer().compare(ctx.resolve(inputs.getFirst()), extraDirs,
                    ctx.resolve(inputs.getLast()), options, Inputs.folderOptions(args), progress,
                    CancellationToken.NEVER);
            StderrProgress.clear(progress);
            FolderComparison exported = c.redacted(options.redactor());
            if (!args.has("quiet")) {
                String ff = folderFormat(args);
                emitFolder(ff.equals(SUMMARY) && outFile == null ? c : exported, ff, Ansi.of(color), glyphs, width,
                        outFile, ctx);
            }
            for (Outputs.Target t : reports) {
                t.write(exported, ctx);
            }
            return switch (exitMode) {
                case "never" -> ExitCode.OK;
                case "problems" -> c.newProblems() == 0 ? ExitCode.OK : ExitCode.DIFFERENT;
                default -> c.identical() ? ExitCode.OK : ExitCode.DIFFERENT;
            };
        }

        if (inputs.stream().filter("-"::equals).count() > 1) {
            throw new UsageException("Standard input ('-') can only be used for one input");
        }
        String format = resolveFormat(args, ctx);
        List<LogFile> logs = new ArrayList<>();
        for (String in : inputs) {
            logs.add(Inputs.load(ctx, in, charset, remote, progress));
        }
        DiffResult result = new LogDeltaEngine().compare(logs.getFirst(), logs.subList(1, logs.size() - 1),
                logs.getLast(), options, progress, CancellationToken.NEVER);
        StderrProgress.clear(progress);
        Outputs.warnAboutWindow(result, ctx, args.has("quiet"));

        // Only what is shown on the terminal keeps secrets; anything written to a file is a report.
        boolean terminalFormat = (format.equals(SIDE_BY_SIDE) || format.equals(SUMMARY)) && outFile == null;
        DiffResult exported = !reports.isEmpty() || !terminalFormat && !args.has("quiet")
                ? Outputs.exportable(result, ctx, args.has("quiet")) : result;
        if (!args.has("quiet")) {
            emit(terminalFormat ? result : exported, format, reportOptions, Ansi.of(color), glyphs, width, outFile,
                    ctx);
        }
        for (Outputs.Target t : reports) {
            t.write(exported, reportOptions, ctx);
        }
        return switch (exitMode) {
            case "never" -> ExitCode.OK;
            case "problems" -> result.insights().newProblems().isEmpty() ? ExitCode.OK : ExitCode.DIFFERENT;
            default -> result.isIdentical() ? ExitCode.OK : ExitCode.DIFFERENT;
        };
    }

    static Session readSession(Path file) throws UsageException {
        try {
            return SessionIO.read(file);
        } catch (java.nio.file.NoSuchFileException e) {
            throw new UsageException("Session not found: " + file);
        } catch (IOException e) {
            throw new UsageException("Cannot read session " + file + ": " + e.getMessage());
        } catch (SessionParseException e) {
            throw new UsageException("Invalid session " + file + ": " + e.getMessage());
        }
    }

    static String choice(ParsedArgs args, String option, String fallback, String... allowed) throws UsageException {
        String v = args.get(option, fallback);
        for (String a : allowed) {
            if (a.equals(v)) {
                return v;
            }
        }
        throw new UsageException("--" + option + " must be " + String.join(", ", List.of(allowed).subList(0,
                allowed.length - 1)) + " or " + allowed[allowed.length - 1]);
    }

    private static String resolveFormat(ParsedArgs args, CliContext ctx) throws UsageException {
        String f = args.get("format");
        if (f == null) {
            if (args.has("output")) {
                ReportFormat rf = Outputs.formatOf(args.get("output"));
                return rf == null ? ReportFormat.UNIFIED.key() : rf.key();
            }
            return ctx.terminal() ? SIDE_BY_SIDE : ReportFormat.UNIFIED.key();
        }
        f = f.toLowerCase(Locale.ROOT);
        if (f.equals(SIDE_BY_SIDE) || f.equals("sbs") || f.equals(SUMMARY)) {
            return f.equals("sbs") ? SIDE_BY_SIDE : f;
        }
        try {
            return ReportFormat.parse(f).key();
        } catch (IllegalArgumentException _) {
            throw new UsageException("Unknown format '" + f
                    + "'. Use side-by-side, unified, summary, html, json, markdown or junit.");
        }
    }

    /// `summary` (the table) or a key of [FolderReports#SUPPORTED].
    private static String folderFormat(ParsedArgs args) throws UsageException {
        String f = args.get("format");
        if (f == null && args.has("output")) {
            ReportFormat rf = Outputs.formatOf(args.get("output"));
            if (rf != null && FolderReports.SUPPORTED.contains(rf)) {
                return rf.key();
            }
            if (rf != null) {
                throw new UsageException(rf.displayName() + " is not available for folder comparisons; "
                        + "use .md, .json or .xml (or -f summary)");
            }
        }
        if (f == null || f.equalsIgnoreCase(SUMMARY)) {
            return SUMMARY;
        }
        try {
            ReportFormat rf = ReportFormat.parse(f);
            if (FolderReports.SUPPORTED.contains(rf)) {
                return rf.key();
            }
        } catch (IllegalArgumentException _) {
            // reported below
        }
        throw new UsageException("Folder comparisons support the formats summary, markdown, json and junit");
    }

    private static void emitFolder(FolderComparison c, String format, Ansi ansi, Glyphs glyphs, int width,
                                   Path outFile, CliContext ctx) throws IOException {
        String text;
        if (format.equals(SUMMARY)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(buffer, true, StandardCharsets.UTF_8);
            new FolderRenderer(ansi, glyphs, width).render(c, ps);
            text = buffer.toString(StandardCharsets.UTF_8);
        } else {
            text = FolderReports.render(c, ReportFormat.parse(format));
        }
        if (outFile != null) {
            Outputs.createParent(outFile);
            Files.writeString(outFile, text, StandardCharsets.UTF_8);
            ctx.err().println("Wrote " + format + " to " + outFile);
        } else {
            ctx.out().print(text);
            ctx.out().flush();
        }
    }

    private static void emit(DiffResult result, String format, ReportOptions options, Ansi ansi, Glyphs glyphs,
                             int width, Path outFile, CliContext ctx) throws IOException {
        if (format.equals(SIDE_BY_SIDE) || format.equals(SUMMARY)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            PrintStream ps = outFile == null ? ctx.out() : new PrintStream(buffer, true, StandardCharsets.UTF_8);
            if (format.equals(SUMMARY)) {
                new SummaryRenderer(ansi, glyphs, width).render(result, ps);
            } else {
                new SideBySideRenderer(ansi, glyphs, width).render(result, options.contextLines(), options.maxHunks(), ps);
            }
            ps.flush();
            if (outFile != null) {
                Outputs.createParent(outFile);
                Files.write(outFile, buffer.toByteArray());
            }
            return;
        }
        ReportFormat rf = ReportFormat.parse(format);
        if (outFile != null) {
            Reports.writeFile(result, rf, options, outFile);
            ctx.err().println("Wrote " + rf.displayName() + " to " + outFile);
        } else {
            ctx.out().print(Reports.writer(rf).render(result, options));
            ctx.out().flush();
        }
    }
}
