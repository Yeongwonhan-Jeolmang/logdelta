// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.render;

import io.github.logdelta.core.Version;
import io.github.logdelta.core.analysis.FlakyLine;
import io.github.logdelta.core.analysis.FrequencyShift;
import io.github.logdelta.core.analysis.InsightItem;
import io.github.logdelta.core.analysis.Insights;
import io.github.logdelta.core.analysis.ProblemTimeline;
import io.github.logdelta.core.analysis.Severity;
import io.github.logdelta.core.analysis.TemplateShift;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.DiffStats;
import io.github.logdelta.core.suggest.RuleSuggestion;
import io.github.logdelta.core.timing.PhaseChange;
import io.github.logdelta.core.timing.Timeline;
import io.github.logdelta.core.timing.TimingAnalysis;
import io.github.logdelta.core.util.LogText;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;

/// Prints the statistics and insights of a comparison in a compact, scannable form.
public final class SummaryRenderer {

    private static final int MAX_ITEMS = 15;

    private final Ansi ansi;
    private final Glyphs glyphs;
    private final int width;

    /// @param ansi   styling
    /// @param glyphs symbol characters
    /// @param width  terminal width used to truncate long lines
    public SummaryRenderer(Ansi ansi, Glyphs glyphs, int width) {
        this.ansi = ansi;
        this.glyphs = glyphs;
        this.width = Math.max(60, width);
    }

    /// Prints the summary.
    public void render(DiffResult r, PrintStream out) {
        DiffStats s = r.stats();
        Insights in = r.insights();
        out.println(ansi.bold(Version.NAME) + "  " + r.leftFile().displayName()
                + ansi.dim(" " + glyphs.arrow() + " ") + r.rightFile().displayName());
        if (!r.extraBaselines().isEmpty()) {
            out.println(ansi.dim("  also compared with " + r.extraBaselines().size() + " more baseline run(s); "
                    + s.expected() + " difference(s) they explain count as normal"));
        }
        String detected = r.detection().describe();
        if (!detected.isEmpty()) {
            out.println(ansi.dim("  format: " + detected));
        }
        if (r.options().timeWindow() != null) {
            out.println(ansi.dim("  time window: " + r.options().timeWindow().describe()
                    + String.format(Locale.ROOT, " (from line %,d / %,d)", r.leftFile().firstLineNumber(),
                    r.rightFile().firstLineNumber())));
        }
        out.println();
        if (s.identical()) {
            out.println("  " + ansi.green("Identical after normalization")
                    + ansi.dim(String.format(Locale.ROOT, "  (%,d vs %,d lines, %d ms)", s.leftLines(), s.rightLines(),
                    r.elapsedMillis())));
            return;
        }
        out.printf(Locale.ROOT, "  %-18s%s%n", "Similarity", ansi.bold(String.format(Locale.ROOT, "%.1f%%",
                s.similarity() * 100)));
        out.printf(Locale.ROOT, "  %-18s%s  %s  %s  %s%n", "Changes",
                ansi.yellow(s.modified() + " modified"),
                ansi.red((s.removed() - s.moved()) + " removed"),
                ansi.green((s.added() - s.moved()) + " added"),
                ansi.magenta(s.moved() + " moved"));
        out.printf(Locale.ROOT, "  %-18s%,d %s %,d%s%n", "Lines", s.leftLines(), glyphs.arrow(), s.rightLines(),
                s.leftIgnored() + s.rightIgnored() > 0
                        ? ansi.dim(String.format(Locale.ROOT, "  (%d / %d ignored)", s.leftIgnored(), s.rightIgnored()))
                        : "");
        if (in.firstDivergenceRow() >= 0) {
            DiffRow row = r.rows().get(in.firstDivergenceRow());
            out.printf(Locale.ROOT, "  %-18sbaseline %s, candidate %s%n", "First divergence",
                    row.hasLeft() ? "line " + r.left(row).number() : "end",
                    row.hasRight() ? "line " + r.right(row).number() : "end");
        }
        out.printf(Locale.ROOT, "  %-18s%s  %s%n", "Errors",
                compare(in.leftCount(Severity.ERROR) + in.leftCount(Severity.FATAL),
                        in.rightCount(Severity.ERROR) + in.rightCount(Severity.FATAL)),
                ansi.dim("Warnings ") + compare(in.leftCount(Severity.WARN), in.rightCount(Severity.WARN)));
        out.println();

        problemTimeline(out, r.problemTimeline());
        items(out, "New problems", in.newProblems(), true);
        items(out, "Resolved problems", in.resolvedProblems(), false);
        shifts(out, in.frequencyShifts());
        templates(out, in.templateShifts());
        timing(out, r.timing());
        flaky(out, in.flakyLines());
        suggestions(out, r.suggestions());
    }

    private void problemTimeline(PrintStream out, ProblemTimeline t) {
        if (t == null || !t.hasProblems()) {
            return;
        }
        String axis = t.byTime() ? "over " + Timeline.format(t.spanMillis()) : "over the file";
        out.println(ansi.bold("Warnings and errors") + " " + ansi.dim("(" + axis + ")"));
        int peak = Math.max(1, t.peak());
        out.println("  " + ansi.dim("baseline  ") + spark(t.left(), peak));
        out.println("  " + ansi.dim("candidate ") + spark(t.right(), peak));
        out.println();
    }

    private String spark(ProblemTimeline.Side side, int peak) {
        String chars = glyphs.sparks();
        int levels = chars.length() - 1;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < side.warnings().length; i++) {
            int n = side.warnings()[i] + side.errors()[i];
            int level = n == 0 ? 0 : Math.max(1, (int) Math.round(n * (double) levels / peak));
            String c = String.valueOf(chars.charAt(level));
            sb.append(side.errors()[i] > 0 ? ansi.red(c) : n > 0 ? ansi.yellow(c) : c);
        }
        return sb + ansi.dim(String.format(Locale.ROOT, "  %d errors, %d warnings", side.totalErrors(),
                side.totalWarnings()));
    }

    private void templates(PrintStream out, List<TemplateShift> shifts) {
        if (shifts.isEmpty()) {
            return;
        }
        out.println(ansi.bold("Message templates") + " " + ansi.dim("(similar messages counted together)"));
        for (TemplateShift t : shifts) {
            String delta = String.format(Locale.ROOT, "%+5d", t.delta());
            delta = t.delta() > 0 ? ansi.red(delta) : ansi.green(delta);
            String counts = String.format(Locale.ROOT, "%4d %s %-4d", t.leftCount(), glyphs.arrow(), t.rightCount());
            out.println("  " + delta + "  " + ansi.dim(counts) + "  " + truncate(t.template(), Math.max(20, width - 24)));
        }
        out.println();
    }

    private void flaky(PrintStream out, List<FlakyLine> lines) {
        if (lines.isEmpty()) {
            return;
        }
        out.println(ansi.bold("Flaky lines") + " " + ansi.dim("(differ between the good runs)"));
        for (FlakyLine f : lines) {
            out.println("  " + ansi.dim(String.format(Locale.ROOT, "L%-6d", f.lineNumber())) + " "
                    + truncate(LogText.message(f.text()), Math.max(20, width - 12)));
            StringBuilder fix = new StringBuilder();
            for (var rule : f.rules()) {
                fix.append(fix.isEmpty() ? "" : " ").append("--mask '").append(rule.regex().replace("'", "'\\''"))
                        .append("=>").append(rule.replacement()).append("'");
            }
            if (fix.isEmpty() && f.ignorePattern() != null) {
                fix.append("-I '").append(f.ignorePattern().replace("'", "'\\''")).append("'");
            }
            String similar = f.similarLines() > 0 ? ", +" + f.similarLines() + " similar line(s)" : "";
            out.println("          " + ansi.dim(f.kind().description() + " (" + f.varyingRuns() + " of "
                    + (f.totalRuns() - 1) + " other runs" + similar + ")" + (fix.isEmpty() ? "" : "  " + fix)));
        }
        out.println();
    }

    private void timing(PrintStream out, TimingAnalysis t) {
        if (!t.available()) {
            return;
        }
        out.println(ansi.bold("Timing") + " " + ansi.dim("(run duration " + Timeline.format(t.left().duration()) + " "
                + glyphs.arrow() + " " + Timeline.format(t.right().duration()) + ")"));
        if (t.slower().isEmpty() && t.faster().isEmpty()) {
            out.println(ansi.dim("  no phase changed notably"));
            out.println();
            return;
        }
        for (PhaseChange p : t.slower()) {
            phase(out, p, true);
        }
        for (PhaseChange p : t.faster()) {
            phase(out, p, false);
        }
        out.println();
    }

    private void phase(PrintStream out, PhaseChange p, boolean slower) {
        String times = String.format(Locale.ROOT, "%8s %s %-8s", Timeline.format(p.leftMillis()), glyphs.arrow(),
                Timeline.format(p.rightMillis()));
        String factor = String.format(Locale.ROOT, "%5.1fx", p.factor());
        String label = LogText.message(p.startText()).strip() + " " + glyphs.arrow() + " "
                + LogText.message(p.endText()).strip();
        out.println("  " + (slower ? ansi.red(factor) : ansi.green(factor)) + "  " + ansi.dim(times) + "  "
                + truncate(label, Math.max(20, width - 34)));
    }

    private void suggestions(PrintStream out, List<RuleSuggestion> suggestions) {
        if (suggestions.isEmpty()) {
            return;
        }
        out.println(ansi.bold("Suggested rules") + " " + ansi.dim("(would hide run-specific differences)"));
        for (RuleSuggestion s : suggestions) {
            String mask = "--mask '" + s.rule().regex().replace("'", "'\\''") + "=>" + s.rule().replacement() + "'";
            out.println("  " + ansi.cyan(String.format(Locale.ROOT, "%3d", s.resolves())) + "  "
                    + truncate(s.summary(), Math.max(20, width - 8)));
            out.println("       " + ansi.dim(mask));
        }
        out.println();
    }

    private String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, max - glyphs.ellipsis().length())) + glyphs.ellipsis();
    }

    private String compare(int l, int r) {
        String text = l + " " + glyphs.arrow() + " " + r;
        if (r > l) {
            return ansi.red(text);
        }
        return r < l ? ansi.green(text) : text;
    }

    private void items(PrintStream out, String title, List<InsightItem> items, boolean bad) {
        if (items.isEmpty()) {
            return;
        }
        out.println(ansi.bold(title) + " " + ansi.dim("(" + items.size() + ")"));
        int n = 0;
        for (InsightItem i : items) {
            if (n++ >= MAX_ITEMS) {
                out.println(ansi.dim("  " + glyphs.fold() + " " + (items.size() - MAX_ITEMS) + " more"));
                break;
            }
            String sev = String.format(Locale.ROOT, "%-5s", i.severity().label());
            sev = i.severity().compareTo(Severity.ERROR) >= 0 ? ansi.red(sev) : ansi.yellow(sev);
            String more = i.lines() > 1 ? ansi.dim(" (+" + (i.lines() - 1) + " lines)") : "";
            if (i.repeated()) {
                more += ansi.bold(" " + (glyphs == Glyphs.UNICODE ? "\u00d7" : "x") + i.occurrences());
            }
            String loc = String.format(Locale.ROOT, "L%-6d", i.lineNumber());
            int room = width - 2 - 5 - 1 - loc.length() - 1 - (i.lines() > 1 ? 14 : 0) - (i.repeated() ? 7 : 0);
            String text = truncate(LogText.message(i.text()), Math.max(20, room));
            out.println("  " + (bad ? sev : ansi.green(String.format(Locale.ROOT, "%-5s", i.severity().label())))
                    + " " + ansi.dim(loc) + " " + text + more);
        }
        out.println();
    }

    private void shifts(PrintStream out, List<FrequencyShift> shifts) {
        if (shifts.isEmpty()) {
            return;
        }
        out.println(ansi.bold("Frequency shifts") + " " + ansi.dim("(occurrences baseline " + glyphs.arrow() + " candidate)"));
        for (FrequencyShift f : shifts) {
            String delta = String.format(Locale.ROOT, "%+5d", f.delta());
            delta = f.delta() > 0 ? ansi.red(delta) : ansi.green(delta);
            String counts = String.format(Locale.ROOT, "%4d %s %-4d", f.leftCount(), glyphs.arrow(), f.rightCount());
            out.println("  " + delta + "  " + ansi.dim(counts) + "  "
                    + truncate(LogText.message(f.template()), Math.max(20, width - 24)));
        }
        out.println();
    }
}
