// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.engine;

import io.github.logdelta.core.analysis.FlakyLine;
import io.github.logdelta.core.analysis.FrequencyShift;
import io.github.logdelta.core.analysis.InsightItem;
import io.github.logdelta.core.analysis.Insights;
import io.github.logdelta.core.analysis.ProblemTimeline;
import io.github.logdelta.core.analysis.TemplateShift;
import io.github.logdelta.core.diff.InlineHighlights;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.DiffStats;
import io.github.logdelta.core.model.Hunk;
import io.github.logdelta.core.model.ProcessedLine;
import io.github.logdelta.core.model.ProcessedLine.StructuredValue;
import io.github.logdelta.core.redact.Redaction;
import io.github.logdelta.core.redact.Redactor;
import io.github.logdelta.core.structured.Field;
import io.github.logdelta.core.structured.StructuredLines;
import io.github.logdelta.core.structured.StructuredLines.FieldChange;
import io.github.logdelta.core.suggest.RuleSuggestion;
import io.github.logdelta.core.timing.PhaseChange;
import io.github.logdelta.core.timing.TimingAnalysis;

import java.util.ArrayList;
import java.util.List;

/// The complete outcome of comparing two logs.
///
/// [#rows()] is the side-by-side view: every compared line of both files appears in
/// exactly one row, in file order. Rows reference lines by index into [#leftLines()] and
/// [#rightLines()]; lines removed by ignore patterns are not part of either list.
public final class DiffResult {

    private final LogFile leftFile;
    private final LogFile rightFile;
    private final List<ProcessedLine> leftLines;
    private final List<ProcessedLine> rightLines;
    private final List<DiffRow> rows;
    private final DiffStats stats;
    private final Insights insights;
    private final DiffOptions options;
    private final long elapsedMillis;
    private final List<LogFile> extraBaselines;
    private final TimingAnalysis timing;
    private final List<RuleSuggestion> suggestions;
    private final Detection detection;
    private final ProblemTimeline problemTimeline;
    private final int redactedSecrets;

    /// Creates a result; normally only called by [LogDeltaEngine].
    ///
    /// @param options        the options that were used, including rules added by a preset
    /// @param extraBaselines additional baseline runs (besides `leftFile`), possibly empty
    /// @param timing         timing of both runs
    /// @param suggestions    rules that would hide some of the differences
    /// @param detection      the detected log format and preset
    /// @param problemTimeline warnings and errors over time
    public DiffResult(LogFile leftFile, LogFile rightFile, List<ProcessedLine> leftLines,
                      List<ProcessedLine> rightLines, List<DiffRow> rows, DiffStats stats, Insights insights,
                      DiffOptions options, long elapsedMillis, List<LogFile> extraBaselines, TimingAnalysis timing,
                      List<RuleSuggestion> suggestions, Detection detection, ProblemTimeline problemTimeline) {
        this(leftFile, rightFile, leftLines, rightLines, rows, stats, insights, options, elapsedMillis,
                extraBaselines, timing, suggestions, detection, problemTimeline, 0);
    }

    private DiffResult(LogFile leftFile, LogFile rightFile, List<ProcessedLine> leftLines,
                       List<ProcessedLine> rightLines, List<DiffRow> rows, DiffStats stats, Insights insights,
                       DiffOptions options, long elapsedMillis, List<LogFile> extraBaselines, TimingAnalysis timing,
                       List<RuleSuggestion> suggestions, Detection detection, ProblemTimeline problemTimeline,
                       int redactedSecrets) {
        this.detection = detection == null ? Detection.PLAIN : detection;
        this.problemTimeline = problemTimeline;
        this.redactedSecrets = redactedSecrets;
        this.extraBaselines = List.copyOf(extraBaselines);
        this.timing = timing;
        this.suggestions = List.copyOf(suggestions);
        this.leftFile = leftFile;
        this.rightFile = rightFile;
        this.leftLines = List.copyOf(leftLines);
        this.rightLines = List.copyOf(rightLines);
        this.rows = List.copyOf(rows);
        this.stats = stats;
        this.insights = insights;
        this.options = options;
        this.elapsedMillis = elapsedMillis;
    }

    public LogFile leftFile() {
        return leftFile;
    }

    public LogFile rightFile() {
        return rightFile;
    }

    /// Compared left lines (ignored lines excluded).
    public List<ProcessedLine> leftLines() {
        return leftLines;
    }

    /// Compared right lines (ignored lines excluded).
    public List<ProcessedLine> rightLines() {
        return rightLines;
    }

    /// Side-by-side rows in file order.
    public List<DiffRow> rows() {
        return rows;
    }

    public DiffStats stats() {
        return stats;
    }

    public Insights insights() {
        return insights;
    }

    public DiffOptions options() {
        return options;
    }

    /// Wall-clock time the comparison took, excluding file reading.
    public long elapsedMillis() {
        return elapsedMillis;
    }

    /// Additional baseline runs; their lines count as normal variation (see [DiffRow#expected()]).
    public List<LogFile> extraBaselines() {
        return extraBaselines;
    }

    /// Timing of both runs and the phases that got slower or faster.
    public TimingAnalysis timing() {
        return timing;
    }

    /// Rules that would hide some of the remaining differences.
    public List<RuleSuggestion> suggestions() {
        return suggestions;
    }

    /// The detected log format and preset.
    public Detection detection() {
        return detection;
    }

    /// Warnings and errors over time in both logs.
    public ProblemTimeline problemTimeline() {
        return problemTimeline;
    }

    /// Number of secrets replaced, if this is a [redacted][#redacted(Redactor)] copy.
    public int redactedSecrets() {
        return redactedSecrets;
    }

    /// The fields that differ in a modified row of structured lines (empty otherwise).
    public List<FieldChange> fieldChanges(DiffRow row) {
        if (!row.hasLeft() || !row.hasRight()) {
            return List.of();
        }
        return StructuredLines.changes(left(row), right(row), options.normalizer());
    }

    /// Returns a copy in which every text that reports show (lines, insights, phase labels,
    /// suggestion examples) has passed through `redactor`. Masks, highlights and field offsets are
    /// moved to match. Comparison keys are left alone.
    public DiffResult redacted(Redactor redactor) {
        if (redactor.isNone()) {
            return this;
        }
        int[] count = {0};
        List<Redaction> leftR = new ArrayList<>(leftLines.size());
        List<Redaction> rightR = new ArrayList<>(rightLines.size());
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        List<ProcessedLine> l = redactLines(leftLines, redactor, leftR, count, salt);
        List<ProcessedLine> r = redactLines(rightLines, redactor, rightR, count, salt);
        List<DiffRow> newRows = new ArrayList<>(rows.size());
        for (DiffRow row : rows) {
            InlineHighlights h = row.inline();
            if (!h.isEmpty()) {
                h = new InlineHighlights(row.hasLeft() ? leftR.get(row.left()).mapSpans(h.left()) : h.left(),
                        row.hasRight() ? rightR.get(row.right()).mapSpans(h.right()) : h.right());
                row = new DiffRow(row.kind(), row.left(), row.right(), h, row.movedPeer(), row.expected());
            }
            newRows.add(row);
        }
        Insights in = insights;
        Insights newInsights = new Insights(items(in.newProblems(), redactor), items(in.resolvedProblems(), redactor),
                in.leftSeverities(), in.rightSeverities(), in.firstDivergenceRow(),
                in.frequencyShifts().stream().map(f -> new FrequencyShift(redactor.apply(f.template()), f.leftCount(),
                        f.rightCount(), f.firstRow())).toList(),
                in.templateShifts().stream().map(t -> new TemplateShift(redactor.apply(t.template()), t.leftCount(),
                        t.rightCount(), t.variants(), t.firstRow(), t.severity())).toList(),
                in.flakyLines().stream().map(f -> new FlakyLine(f.kind(), f.lineNumber(), redactor.apply(f.text()),
                        f.varyingRuns(), f.totalRuns(), f.rules(), redactPattern(f.ignorePattern(), redactor),
                        f.example() == null ? null : redactor.apply(f.example()), f.similarLines())).toList());
        TimingAnalysis t = timing;
        TimingAnalysis newTiming = t == null ? null : new TimingAnalysis(t.left(), t.right(),
                phases(t.slower(), redactor), phases(t.faster(), redactor));
        List<RuleSuggestion> newSuggestions = suggestions.stream().map(s -> new RuleSuggestion(s.rule(), s.summary(),
                s.resolves(), s.exampleRow(), redactor.apply(s.exampleLeft()), redactor.apply(s.exampleRight())))
                .toList();
        return new DiffResult(leftFile, rightFile, l, r, newRows, stats, newInsights, options, elapsedMillis,
                extraBaselines, newTiming, newSuggestions, detection, problemTimeline, count[0]);
    }

    private static String digest(byte[] salt, String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(salt);
            byte[] d = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /// Removes secrets from a `\\Q...\\E` ignore pattern made from a line's text. Each removed
    /// secret becomes `.+?`, so the pattern still matches the lines it was made for.
    static String redactPattern(String pattern, Redactor redactor) {
        if (pattern == null || !pattern.startsWith("\\Q") || !pattern.endsWith("\\E")) {
            return pattern;
        }
        String literal = pattern.substring(2, pattern.length() - 2);
        String red = redactor.apply(literal);
        if (red.equals(literal)) {
            return pattern;
        }
        StringBuilder sb = new StringBuilder();
        String[] parts = red.split(java.util.regex.Pattern.quote(Redactor.REPLACEMENT), -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(".+?");
            }
            if (!parts[i].isEmpty()) {
                sb.append(java.util.regex.Pattern.quote(parts[i]));
            }
        }
        return sb.toString();
    }

    private static List<ProcessedLine> redactLines(List<ProcessedLine> lines, Redactor redactor,
                                                   List<Redaction> out, int[] count, byte[] salt) {
        List<ProcessedLine> result = new ArrayList<>(lines.size());
        for (ProcessedLine p : lines) {
            Redaction red = redactor.redact(p.text());
            out.add(red);
            if (!red.changed()) {
                result.add(p);
                continue;
            }
            count[0] += red.count();
            List<StructuredValue> fields = new ArrayList<>(p.fields().size());
            for (StructuredValue v : p.fields()) {
                Field f = v.field();
                int keyStart = red.map(f.keyStart(), true);
                int valueStart = Math.max(keyStart, red.map(f.valueStart(), true));
                int valueEnd = Math.max(valueStart, red.map(f.valueEnd(), false));
                String value = redactor.apply(f.value());
                String normalized = redactor.apply(v.normalized());
                // A value can be a secret because of its field's name (`"password":"hunter2"`), which
                // the value alone does not show: then the line's redaction covers it.
                boolean secret = !value.equals(f.value())
                        || red.text().substring(valueStart, Math.min(valueEnd, red.text().length()))
                                .contains(Redactor.REPLACEMENT);
                if (secret) {
                    if (value.equals(f.value())) {
                        value = Redactor.REPLACEMENT;
                    }
                    // Keep different secrets different, so field changes are still found, without
                    // anything readable: a salted hash that only lives in this copy.
                    normalized = Redactor.REPLACEMENT + "#" + digest(salt, v.normalized());
                }
                fields.add(new StructuredValue(new Field(f.name(), value, keyStart, valueStart, valueEnd),
                        normalized, v.ignored()));
            }
            result.add(new ProcessedLine(p.number(), red.text(), redactor.apply(p.normalized()),
                    red.mapMasks(p.masks()), p.key(), fields));
        }
        return result;
    }

    private static List<InsightItem> items(List<InsightItem> items, Redactor redactor) {
        return items.stream().map(i -> new InsightItem(i.row(), i.lineNumber(), i.severity(),
                redactor.apply(i.text()), i.lines(), i.occurrences(),
                i.template() == null ? null : redactor.apply(i.template()))).toList();
    }

    private static List<PhaseChange> phases(List<PhaseChange> phases, Redactor redactor) {
        return phases.stream().map(p -> new PhaseChange(p.startRow(), p.endRow(), p.leftMillis(), p.rightMillis(),
                redactor.apply(p.startText()), redactor.apply(p.endText()))).toList();
    }

    /// Diff row that shows a given left line number (1-based), or `-1`.
    public int rowOfLeftLine(int lineNumber) {
        for (int i = 0; i < rows.size(); i++) {
            DiffRow r = rows.get(i);
            if (r.hasLeft() && leftLines.get(r.left()).number() == lineNumber) {
                return i;
            }
        }
        return -1;
    }

    /// Diff row that shows a given right line number (1-based), or `-1`.
    public int rowOfRightLine(int lineNumber) {
        for (int i = 0; i < rows.size(); i++) {
            DiffRow r = rows.get(i);
            if (r.hasRight() && rightLines.get(r.right()).number() == lineNumber) {
                return i;
            }
        }
        return -1;
    }

    /// Whether the logs are equal after normalization.
    public boolean isIdentical() {
        return stats.identical();
    }

    /// Left line of a row, or `null` if the row has no left side.
    public ProcessedLine left(DiffRow row) {
        return row.hasLeft() ? leftLines.get(row.left()) : null;
    }

    /// Right line of a row, or `null` if the row has no right side.
    public ProcessedLine right(DiffRow row) {
        return row.hasRight() ? rightLines.get(row.right()) : null;
    }

    /// Groups changed rows into hunks with `context` unchanged rows on each side.
    /// Hunks whose context would touch or overlap are merged.
    public List<Hunk> hunks(int context) {
        int ctx = Math.max(0, context);
        List<Hunk> hunks = new ArrayList<>();
        int start = -1;
        int end = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).isChange()) {
                continue;
            }
            int s = Math.max(0, i - ctx);
            int e = Math.min(rows.size(), i + ctx + 1);
            if (start >= 0 && s <= end) {
                end = Math.max(end, e);
            } else {
                if (start >= 0) {
                    hunks.add(new Hunk(start, end));
                }
                start = s;
                end = e;
            }
        }
        if (start >= 0) {
            hunks.add(new Hunk(start, end));
        }
        return hunks;
    }

    /// Indices of rows that start a block of consecutive changes; used for navigation.
    public List<Integer> changeBlockStarts() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).isChange() && (i == 0 || !rows.get(i - 1).isChange())) {
                out.add(i);
            }
        }
        return out;
    }
}
