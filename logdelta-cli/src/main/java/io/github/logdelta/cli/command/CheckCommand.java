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
import io.github.logdelta.cli.render.SummaryRenderer;
import io.github.logdelta.core.analysis.InsightItem;
import io.github.logdelta.core.analysis.Severity;
import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.engine.LogDeltaEngine;
import io.github.logdelta.core.folder.FolderComparer;
import io.github.logdelta.core.folder.FolderComparison;
import io.github.logdelta.core.folder.FolderEntry;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.io.LogSources;
import io.github.logdelta.core.report.FolderReports;
import io.github.logdelta.core.report.ReportFormat;
import io.github.logdelta.core.report.ReportOptions;
import io.github.logdelta.core.report.Reports;
import io.github.logdelta.core.util.CancellationToken;
import io.github.logdelta.core.util.LogText;
import io.github.logdelta.core.util.ProgressListener;
import io.github.logdelta.core.util.Text;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/// `logdelta check`: a CI gate that compares a run with known-good baselines and fails only
/// when something *new* goes wrong.
///
/// Unlike `diff`, which reports any difference, `check` passes as long as the candidate
/// has no new warnings or errors (configurable with `--fail-on`). Baselines are either
/// files (`--baseline`) or a folder of accepted runs (`--baseline-dir`) that
/// `--update-baseline` / `--update-on-pass` maintain, so the gate learns what normal
/// variation looks like over time.
public final class CheckCommand implements Command {

    private static final String SUMMARY = "summary";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final int MAX_ANNOTATIONS = 50;
    /// Names of runs stored by `--update-baseline` / `--update-on-pass` in a baseline folder.
    private static final java.util.regex.Pattern STORED_RUN = java.util.regex.Pattern.compile("\\d{8}-\\d{6}_.+");

    @Override
    public String name() {
        return "check";
    }

    @Override
    public String summary() {
        return "CI gate: fail only when a run has new problems compared with known-good runs";
    }

    @Override
    public String usage() {
        return "check [options] (--baseline FILE... | --baseline-dir DIR) CANDIDATE";
    }

    @Override
    public String details() {
        return """
                Baselines:
                  --baseline FILE      a known-good run; repeat for several (the first is shown
                                       in reports, the others teach what normal variation is)
                  --baseline-dir DIR   every file in DIR is a known-good run, newest first
                  CANDIDATE may be a folder; then --baseline must name a folder as well.

                Updating:
                  --update-baseline    accept the candidate as the new baseline and pass
                  --update-on-pass     update only when the check passes (rolling baseline)
                  Either option creates a missing baseline from the candidate, so the first
                  CI run bootstraps itself.
                  With --baseline-dir, the candidate is added to the folder and only the
                  newest --keep runs are kept; with --baseline FILE, the file is replaced.

                Failing:
                  --fail-on problems   new warnings or errors (default)
                  --fail-on errors     new errors only
                  --fail-on changes    any unexpected difference

                Exit status: 0 = pass, 1 = fail, 2 = error.

                Examples:
                  logdelta check --baseline good.log build/app.log
                  logdelta check --baseline-dir .logdelta/baselines --update-on-pass \\
                      --report junit:build/logdelta.xml --report summary.md build/app.log
                  logdelta check --baseline logs/expected logs/actual --include '*.log'
                """;
    }

    @Override
    public ArgParser parser() {
        ArgParser p = new ArgParser()
                .add(OptionSpec.repeated("baseline", 'b', "FILE", "known-good run (file, folder or URL)"))
                .add(OptionSpec.value("baseline-dir", 'B', "DIR", "folder of known-good runs"))
                .add(OptionSpec.flag("update-baseline", 'u', "accept the candidate as the new baseline"))
                .add(OptionSpec.flag("update-on-pass", '\0', "update the baseline only when the check passes"))
                .add(OptionSpec.value("keep", '\0', "N", "runs to keep in --baseline-dir (default 5)"))
                .add(OptionSpec.value("fail-on", '\0', "WHAT", "problems (default), errors or changes"))
                .add(OptionSpec.value("format", 'f', "FMT", "summary (default), markdown, json or junit"))
                .add(OptionSpec.value("output", 'o', "FILE", "write the output to FILE"))
                .add(OptionSpec.repeated("report", 'r', "[FMT:]FILE",
                        "also write a report; format from the extension (.html .json .md .xml .diff)"))
                .add(OptionSpec.flag("github", '\0',
                        "emit GitHub Actions annotations and append to the job summary"))
                .add(OptionSpec.value("algorithm", 'a', "NAME", "myers (default) or patience"))
                .add(OptionSpec.flag("multiline", 'M', "treat a line and its continuation lines as one entry"));
        OptionSupport.addNormalizationOptions(p);
        Inputs.addFolderOptions(p);
        Inputs.addSourceOptions(p);
        return p
                .add(OptionSpec.value("encoding", 'e', "CHARSET", "input encoding (default: auto-detect)"))
                .add(OptionSpec.value("width", '\0', "N", "terminal width (default $COLUMNS or 160)"))
                .add(OptionSpec.value("color", '\0', "WHEN", "auto (default), always or never"))
                .add(OptionSpec.flag("quiet", 'q', "print only the verdict"))
                .add(OptionSpec.flag("progress", '\0', "show progress on standard error"))
                .add(OptionSpec.flag("help", 'h', "show this help"));
    }

    @Override
    public int execute(ParsedArgs args, CliContext ctx) throws Exception {
        if (args.positionals().size() != 1) {
            throw new UsageException("Expected exactly one CANDIDATE, got " + args.positionals().size());
        }
        String candidate = args.positionals().getFirst();
        if (!args.has("baseline") && !args.has("baseline-dir")) {
            throw new UsageException("Give at least one --baseline FILE or a --baseline-dir DIR");
        }
        if (args.has("baseline") && args.has("baseline-dir")) {
            throw new UsageException("Use either --baseline or --baseline-dir, not both");
        }
        if (args.all("baseline").contains("-")) {
            throw new UsageException("A baseline cannot be read from standard input; '-' works for the candidate");
        }
        Settings s = new Settings(args, ctx);
        if (Inputs.isDirectory(ctx, candidate)) {
            if (args.has("baseline-dir")) {
                throw new UsageException("For a candidate folder, give the baseline folder with --baseline");
            }
            return checkFolder(args, ctx, s, candidate);
        }
        return checkFile(args, ctx, s, candidate);
    }

    /// Option values shared by file and folder mode.
    private record Settings(DiffOptions options, String failOn, boolean update, boolean updateOnPass, int keep,
                            String format, Path outFile, List<Outputs.Target> reports, Ansi ansi, Glyphs glyphs,
                            int width, Charset charset, LogSources.RemoteOptions remote, ProgressListener progress) {

        Settings(ParsedArgs args, CliContext ctx) throws UsageException {
            this(OptionSupport.buildOptions(args, ctx),
                    DiffCommand.choice(args, "fail-on", "problems", "problems", "errors", "changes"),
                    args.has("update-baseline"), args.has("update-on-pass"), keep(args), format(args),
                    args.has("output") ? ctx.resolve(args.get("output")) : null, targets(args, ctx),
                    Ansi.of(!args.has("output") && ctx.useColor(DiffCommand.choice(args, "color", "auto", "auto",
                            "always", "never"))),
                    Glyphs.of(args.has("output") || ctx.unicode()), args.getInt("width", ctx.terminalWidth(160)),
                    OptionSupport.charset(args), Inputs.remoteOptions(args),
                    StderrProgress.of(args.has("progress"), ctx.err()));
            if (update && updateOnPass) {
                throw new UsageException("Use either --update-baseline or --update-on-pass, not both");
            }
        }

        private static int keep(ParsedArgs args) throws UsageException {
            int k = args.getInt("keep", 5);
            if (k < 1) {
                throw new UsageException("--keep must be at least 1");
            }
            return k;
        }

        private static String format(ParsedArgs args) throws UsageException {
            String f = args.get("format", SUMMARY).toLowerCase(Locale.ROOT);
            if (f.equals(SUMMARY)) {
                return f;
            }
            try {
                ReportFormat rf = ReportFormat.parse(f);
                if (FolderReports.SUPPORTED.contains(rf)) {
                    return rf.key();
                }
            } catch (IllegalArgumentException _) {
                // reported below
            }
            throw new UsageException("check supports the formats summary, markdown, json and junit; "
                    + "use --report for other reports");
        }

        private static List<Outputs.Target> targets(ParsedArgs args, CliContext ctx) throws UsageException {
            List<Outputs.Target> out = new ArrayList<>();
            for (String r : args.all("report")) {
                out.add(Outputs.target(r, ctx));
            }
            return out;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Single log
    // ------------------------------------------------------------------------------------------

    private int checkFile(ParsedArgs args, CliContext ctx, Settings s, String candidate) throws Exception {
        List<String> baselines = new ArrayList<>(args.all("baseline"));
        Path baselineDir = args.has("baseline-dir") ? ctx.resolve(args.get("baseline-dir")) : null;
        if (baselineDir != null) {
            baselines.addAll(runsIn(baselineDir).stream().map(Path::toString).toList());
        }
        for (String b : baselines) {
            if (Inputs.isDirectory(ctx, b)) {
                throw new UsageException(b + " is a folder; a folder baseline needs a folder candidate");
            }
        }

        LogFile cand = Inputs.load(ctx, candidate, s.charset(), s.remote(), s.progress());
        List<String> missing = baselines.stream()
                .filter(b -> !LogSources.isUrl(b) && !Files.exists(ctx.resolve(b))).toList();
        if (!missing.isEmpty() && missing.size() < baselines.size()) {
            throw new UsageException("Baseline not found: " + String.join(", ", missing));
        }
        if (baselines.isEmpty() || !missing.isEmpty()) {
            if (!s.update() && !s.updateOnPass()) {
                String what = baselines.isEmpty() ? "No baseline runs in " + baselineDir
                        : "Baseline not found: " + String.join(", ", missing);
                throw new UsageException(what + " (add --update-baseline or --update-on-pass to create it)");
            }
            Path created = storeBaseline(ctx, candidate, cand, baselines, baselineDir, s.keep());
            verdictStream(ctx, s).println(verdict(s.ansi(), s.glyphs(), true, "baseline created: " + created));
            return ExitCode.OK;
        }

        List<LogFile> logs = new ArrayList<>();
        for (String b : baselines) {
            logs.add(Inputs.load(ctx, b, s.charset(), s.remote(), s.progress()));
        }
        DiffResult result = new LogDeltaEngine().compare(logs.getFirst(), logs.subList(1, logs.size()), cand,
                s.options(), s.progress(), CancellationToken.NEVER);
        StderrProgress.clear(s.progress());

        List<InsightItem> failing = failing(result.insights().newProblems(), s.failOn());
        boolean pass = s.failOn().equals("changes") ? result.isIdentical() : failing.isEmpty();

        if (!args.has("quiet")) {
            String text;
            if (s.format().equals(SUMMARY)) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                PrintStream ps = new PrintStream(buffer, true, StandardCharsets.UTF_8);
                new SummaryRenderer(s.ansi(), s.glyphs(), s.width()).render(result, ps);
                text = buffer.toString(StandardCharsets.UTF_8);
            } else {
                text = Reports.writer(ReportFormat.parse(s.format())).render(result, ReportOptions.DEFAULT);
            }
            write(ctx, s.outFile(), text);
        }
        for (Outputs.Target t : s.reports()) {
            t.write(result, ReportOptions.DEFAULT, ctx);
        }
        if (args.has("github")) {
            github(ctx, result.rightFile().displayName(), failing,
                    Reports.writer(ReportFormat.MARKDOWN).render(result, ReportOptions.DEFAULT.withMaxHunks(5)));
        }

        String reason = pass ? passReason(s.failOn())
                : s.failOn().equals("changes") ? result.stats().unexpectedChanges() + " unexpected change(s)"
                : failing.size() + " new " + (s.failOn().equals("errors") ? "error(s)" : "problem(s)");
        boolean accept = s.update() || s.updateOnPass() && pass;
        if (accept) {
            Path stored = storeBaseline(ctx, candidate, cand, baselines, baselineDir, s.keep());
            reason += "; baseline updated: " + stored;
        }
        verdictStream(ctx, s).println(verdict(s.ansi(), s.glyphs(), pass || s.update(), reason
                + (!pass && s.update() ? " (accepted by --update-baseline)" : "")));
        return pass || s.update() ? ExitCode.OK : ExitCode.DIFFERENT;
    }

    /// Files in a baseline folder, newest first (by modification time, then name).
    static List<Path> runsIn(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(CheckCommand::modified).reversed()
                            .thenComparing(Comparator.comparing(Path::toString).reversed()))
                    .toList();
        }
    }

    private static FileTime modified(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException _) {
            return FileTime.fromMillis(0);
        }
    }

    /// Stores the candidate as a baseline: replaces the first `--baseline` file, or adds a run to
    /// the baseline folder and prunes old runs.
    private static Path storeBaseline(CliContext ctx, String candidateSpec, LogFile cand, List<String> baselines,
                                      Path baselineDir, int keep) throws IOException, UsageException {
        Path target;
        if (baselineDir != null) {
            Files.createDirectories(baselineDir);
            String name = cand.displayName().replaceAll("[\\\\/:*?\"<>|]", "_");
            target = baselineDir.resolve(LocalDateTime.now().format(STAMP) + "_" + name);
        } else {
            String first = baselines.getFirst();
            if (LogSources.isUrl(first)) {
                throw new UsageException("Cannot update a baseline that is a URL: " + first);
            }
            target = ctx.resolve(first);
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        }
        boolean localCandidate = !candidateSpec.equals("-") && !LogSources.isUrl(candidateSpec);
        if (localCandidate) {
            Files.copy(ctx.resolve(candidateSpec), target, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.write(target, cand.lines(), StandardCharsets.UTF_8);
        }
        if (baselineDir != null) {
            // Only runs this command stored are pruned; other files in the folder are left alone.
            List<Path> runs = runsIn(baselineDir).stream()
                    .filter(p -> STORED_RUN.matcher(p.getFileName().toString()).matches()).toList();
            for (Path old : runs.subList(Math.min(keep, runs.size()), runs.size())) {
                if (!old.equals(target)) {
                    Files.deleteIfExists(old);
                }
            }
        }
        return target;
    }

    // ------------------------------------------------------------------------------------------
    // Folders
    // ------------------------------------------------------------------------------------------

    private int checkFolder(ParsedArgs args, CliContext ctx, Settings s, String candidate) throws Exception {
        List<String> baselines = args.all("baseline");
        Path candDir = ctx.resolve(candidate);
        Path baseDir = ctx.resolve(baselines.getFirst());
        List<Path> extraDirs = new ArrayList<>();
        for (String b : baselines.subList(1, baselines.size())) {
            if (!Inputs.isDirectory(ctx, b)) {
                throw new UsageException("Extra baselines for a folder candidate must be folders: " + b);
            }
            extraDirs.add(ctx.resolve(b));
        }
        if (Files.isRegularFile(baseDir)) {
            throw new UsageException(baselines.getFirst() + " is a file; a candidate folder needs a baseline folder");
        }
        if (!Files.isDirectory(baseDir)) {
            if (Files.exists(baseDir) || !s.update() && !s.updateOnPass()) {
                throw new UsageException("Baseline folder not found: " + baselines.getFirst()
                        + " (add --update-baseline or --update-on-pass to create it)");
            }
            int copied = mirror(candDir, baseDir, null, args);
            verdictStream(ctx, s).println(verdict(s.ansi(), s.glyphs(), true, "baseline folder created with " + copied
                    + " file(s): " + baseDir));
            return ExitCode.OK;
        }

        FolderComparison c = new FolderComparer().compare(baseDir, extraDirs, candDir, s.options(),
                Inputs.folderOptions(args), s.progress(), CancellationToken.NEVER);
        StderrProgress.clear(s.progress());

        List<InsightItem> failing = new ArrayList<>();
        for (FolderEntry e : c.entries()) {
            failing.addAll(failing(e.newProblems(), s.failOn()));
        }
        int failed = c.count(FolderEntry.Status.FAILED);
        boolean pass = failed == 0 && (s.failOn().equals("changes") ? c.identical() : failing.isEmpty());

        if (!args.has("quiet")) {
            String text;
            if (s.format().equals(SUMMARY)) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                PrintStream ps = new PrintStream(buffer, true, StandardCharsets.UTF_8);
                new FolderRenderer(s.ansi(), s.glyphs(), s.width()).render(c, ps);
                text = buffer.toString(StandardCharsets.UTF_8);
            } else {
                text = FolderReports.render(c, ReportFormat.parse(s.format()));
            }
            write(ctx, s.outFile(), text);
        }
        for (Outputs.Target t : s.reports()) {
            t.write(c, ctx);
        }
        if (args.has("github")) {
            List<InsightItem> annotate = new ArrayList<>();
            for (FolderEntry e : c.entries()) {
                for (InsightItem i : failing(e.newProblems(), s.failOn())) {
                    annotate.add(new InsightItem(i.row(), i.lineNumber(), i.severity(), e.name() + ": " + i.text(),
                            i.lines()));
                }
            }
            github(ctx, candDir.getFileName() == null ? candidate : candDir.getFileName().toString(), annotate,
                    FolderReports.render(c, ReportFormat.MARKDOWN));
        }

        String reason = failed > 0 ? failed + " file(s) could not be compared"
                : pass ? passReason(s.failOn())
                : s.failOn().equals("changes") ? "the folders differ"
                : failing.size() + " new " + (s.failOn().equals("errors") ? "error(s)" : "problem(s)");
        if (s.update() || s.updateOnPass() && pass) {
            int copied = mirror(candDir, baseDir, c, args);
            reason += "; baseline updated (" + copied + " file(s))";
        }
        verdictStream(ctx, s).println(verdict(s.ansi(), s.glyphs(), pass || s.update(), reason
                + (!pass && s.update() ? " (accepted by --update-baseline)" : "")));
        return pass || s.update() ? ExitCode.OK : ExitCode.DIFFERENT;
    }

    /// Copies the candidate's files into the baseline folder. For rotated pairs (different
    /// names), the old baseline file is removed so that the next run pairs unambiguously.
    /// Files that exist only in the baseline are kept.
    private static int mirror(Path candDir, Path baseDir, FolderComparison c, ParsedArgs args) throws IOException {
        int copied = 0;
        for (Path src : listFiles(candDir, args)) {
            Path dst = baseDir.resolve(candDir.relativize(src).toString());
            Path parent = dst.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            copied++;
        }
        if (c != null) {
            for (FolderEntry e : c.entries()) {
                if (e.left() != null && e.right() != null
                        && !e.left().getFileName().equals(e.right().getFileName())) {
                    Files.deleteIfExists(e.left());
                }
            }
        }
        return copied;
    }

    private static List<Path> listFiles(Path dir, ParsedArgs args) throws IOException {
        return FolderComparer.listFiles(dir, Inputs.folderOptions(args));
    }

    // ------------------------------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------------------------------

    private static List<InsightItem> failing(List<InsightItem> problems, String failOn) {
        if (failOn.equals("errors")) {
            return problems.stream().filter(i -> i.severity().compareTo(Severity.ERROR) >= 0).toList();
        }
        return problems;
    }

    private static String passReason(String failOn) {
        return switch (failOn) {
            case "errors" -> "no new errors";
            case "changes" -> "no unexpected changes";
            default -> "no new problems";
        };
    }

    private static String verdict(Ansi ansi, Glyphs glyphs, boolean pass, String reason) {
        boolean unicode = glyphs == Glyphs.UNICODE;
        String mark = pass ? (unicode ? "✔ PASS" : "PASS") : (unicode ? "✘ FAIL" : "FAIL");
        return (pass ? ansi.green(ansi.bold(mark)) : ansi.red(ansi.bold(mark))) + "  " + reason;
    }

    /// Standard output, unless it carries a machine-readable report (JSON, JUnit, Markdown) that a
    /// verdict line would corrupt; then standard error.
    private static PrintStream verdictStream(CliContext ctx, Settings s) {
        return s.format().equals(SUMMARY) || s.outFile() != null ? ctx.out() : ctx.err();
    }

    private static void write(CliContext ctx, Path outFile, String text) throws IOException {
        if (outFile != null) {
            Path parent = outFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outFile, text, StandardCharsets.UTF_8);
            ctx.err().println("Wrote " + outFile);
        } else {
            ctx.out().print(text);
            ctx.out().flush();
        }
    }

    /// Writes workflow-command annotations and appends `markdown` to `$GITHUB_STEP_SUMMARY`.
    private static void github(CliContext ctx, String candidate, List<InsightItem> problems, String markdown)
            throws IOException {
        int n = 0;
        for (InsightItem i : problems) {
            if (n++ >= MAX_ANNOTATIONS) {
                break;
            }
            String level = i.severity().compareTo(Severity.ERROR) >= 0 ? "error" : "warning";
            String title = "New " + i.severity().label() + " in " + candidate;
            String message = "line " + i.lineNumber() + ": " + Text.truncate(LogText.message(i.text()).strip(), 300);
            ctx.out().println("::" + level + " title=" + escapeProperty(title) + "::" + escapeData(message));
        }
        String summaryFile = ctx.env().get("GITHUB_STEP_SUMMARY");
        if (summaryFile != null && !summaryFile.isBlank()) {
            Files.writeString(Path.of(summaryFile), markdown + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    /// Escapes a workflow command message.
    static String escapeData(String s) {
        return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
    }

    /// Escapes a workflow command property value.
    static String escapeProperty(String s) {
        return escapeData(s).replace(":", "%3A").replace(",", "%2C");
    }
}
