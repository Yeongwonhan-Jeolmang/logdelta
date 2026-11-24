// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.command;

import io.github.logdelta.cli.CliContext;
import io.github.logdelta.cli.ExitCode;
import io.github.logdelta.cli.args.ArgParser;
import io.github.logdelta.cli.args.OptionSpec;
import io.github.logdelta.cli.args.ParsedArgs;
import io.github.logdelta.cli.render.Ansi;
import io.github.logdelta.cli.render.Glyphs;
import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.LogDeltaEngine;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.model.ProcessedLine;
import io.github.logdelta.core.normalize.Mask;
import io.github.logdelta.core.normalize.Rule;
import io.github.logdelta.core.structured.StructuredLines;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/// `logdelta explain`: shows, for individual lines, which rule masked which characters.
/// The quickest way to debug a custom rule.
///
/// ```
/// $ logdelta explain "2026-11-12 08:49:03 pid=42 took 3ms"
///   2026-11-12 08:49:03 pid=42 took 3ms
///   ───────────────────      ──     ───
///   [0..19]  iso-timestamp (ISO-8601 timestamp)  → <TIMESTAMP>
///   [20..26] pid (Process / thread id)           → pid=<N>
///   [32..35] duration (Duration)                 → <DURATION>
///   = <TIMESTAMP> pid=<N> took <DURATION>
/// ```
public final class ExplainCommand implements Command {

    @Override
    public String name() {
        return "explain";
    }

    @Override
    public String summary() {
        return "show which rules match a line";
    }

    @Override
    public String usage() {
        return "explain [options] [LINE...]";
    }

    @Override
    public String details() {
        return """
                Without LINE arguments, lines are read from standard input.

                Example:
                  logdelta explain --mask 'ORD-\\d+' "Order ORD-123456 placed at 2026-11-12T08:49:03Z"
                """;
    }

    @Override
    public ArgParser parser() {
        ArgParser p = new ArgParser();
        OptionSupport.addNormalizationOptions(p);
        return p.add(OptionSpec.value("color", '\0', "WHEN", "auto (default), always or never"))
                .add(OptionSpec.flag("help", 'h', "show this help"));
    }

    @Override
    public int execute(ParsedArgs args, CliContext ctx) throws Exception {
        DiffOptions options = OptionSupport.buildOptions(args, ctx);
        Ansi ansi = Ansi.of(ctx.useColor(args.get("color", "auto")));
        Glyphs glyphs = Glyphs.of(ctx.unicode());
        List<String> lines = new ArrayList<>(args.positionals());
        if (lines.isEmpty()) {
            BufferedReader r = new BufferedReader(new InputStreamReader(ctx.in(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
            }
        }
        PrintStream out = ctx.out();
        // Prepare the lines like a comparison would, so JSON/logfmt lines and presets are explained
        // as they are compared. Every line is explained, even one an ignore pattern would drop.
        LogDeltaEngine.Prepared prepared = LogDeltaEngine.prepare(LogFile.ofLines("explain", lines),
                options.toBuilder().ignorePatterns(List.of()).timeWindow(null).build());
        if (prepared.detection().format().isStructured() || prepared.detection().preset() != null) {
            out.println(ansi.dim("  format: " + prepared.detection().describe()));
            out.println();
        }
        for (int i = 0; i < lines.size(); i++) {
            explain(lines.get(i), prepared.lines().get(i), prepared.options(), ansi, glyphs, out);
        }
        return ExitCode.OK;
    }

    private static void explain(String line, ProcessedLine n, DiffOptions options, Ansi ansi, Glyphs glyphs,
                                PrintStream out) {
        out.println("  " + line);
        if (n.masks().isEmpty() && n.key().equals(line)) {
            out.println(ansi.dim("  (no rule matched)"));
            out.println();
            return;
        }
        StringBuilder underline = new StringBuilder("  ");
        int pos = 0;
        for (Mask m : n.masks()) {
            underline.append(" ".repeat(m.start() - pos)).append(glyphs.horizontal().repeat(Math.max(1, m.length())));
            pos = m.end();
        }
        out.println(ansi.cyan(underline.toString()));
        int rangeWidth = 0;
        int idWidth = 0;
        for (Mask m : n.masks()) {
            rangeWidth = Math.max(rangeWidth, ("[" + m.start() + ".." + m.end() + "]").length());
            idWidth = Math.max(idWidth, m.ruleId().length());
        }
        for (Mask m : n.masks()) {
            Rule rule = options.rules().find(m.ruleId());
            String range = String.format("%-" + rangeWidth + "s", "[" + m.start() + ".." + m.end() + "]");
            String id = String.format("%-" + idWidth + "s", m.ruleId());
            String what = rule != null ? rule.name()
                    : m.ruleId().equals(StructuredLines.IGNORED_FIELD_RULE) ? "Ignored field (--compare-field to keep it)"
                    : "";
            out.println("  " + ansi.cyan(range) + "  " + id + "  " + ansi.dim(glyphs.arrow()) + " "
                    + String.format("%-12s", m.replacement()) + ansi.dim(what));
        }
        // Structured keys separate fields with U+001F; show them readably.
        out.println("  " + ansi.dim("=") + " " + ansi.bold(n.key().replace("\u001f", "  ")));
        out.println();
    }
}
