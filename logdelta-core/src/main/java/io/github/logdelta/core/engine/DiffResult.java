// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.engine;

import io.github.logdelta.core.analysis.Insights;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.DiffStats;
import io.github.logdelta.core.model.Hunk;
import io.github.logdelta.core.model.ProcessedLine;
import io.github.logdelta.core.suggest.RuleSuggestion;
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

    /// Creates a result; normally only called by [LogDeltaEngine].
    ///
    /// @param extraBaselines additional baseline runs (besides `leftFile`), possibly empty
    /// @param timing         timing of both runs
    /// @param suggestions    rules that would hide some of the differences
    public DiffResult(LogFile leftFile, LogFile rightFile, List<ProcessedLine> leftLines,
                      List<ProcessedLine> rightLines, List<DiffRow> rows, DiffStats stats, Insights insights,
                      DiffOptions options, long elapsedMillis, List<LogFile> extraBaselines, TimingAnalysis timing,
                      List<RuleSuggestion> suggestions) {
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
