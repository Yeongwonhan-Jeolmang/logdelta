// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.analysis;

import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.ProcessedLine;
import io.github.logdelta.core.model.RowKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Derives [Insights] from the rows of a comparison.
public final class InsightAnalyzer {

    /// Maximum number of frequency shifts reported.
    public static final int MAX_SHIFTS = 15;

    private static final int MIN_SHIFT = 3;

    private InsightAnalyzer() {
    }

    /// Analyzes a comparison with a single baseline.
    ///
    /// @param left  processed left lines
    /// @param right processed right lines
    /// @param rows  rows referencing `left` and `right`
    public static Insights analyze(List<ProcessedLine> left, List<ProcessedLine> right, List<DiffRow> rows) {
        return analyze(left, right, rows, List.of());
    }

    /// Analyzes a comparison.
    ///
    /// Rows marked [DiffRow#expected()] are never reported as new or resolved problems. A frequency
    /// shift is reported only when the candidate's count lies outside the range of all baselines.
    ///
    /// @param left        processed lines of the main baseline
    /// @param right       processed candidate lines
    /// @param rows        rows referencing `left` and `right`
    /// @param extraCounts occurrences of each comparison key in every additional baseline
    public static Insights analyze(List<ProcessedLine> left, List<ProcessedLine> right, List<DiffRow> rows,
                                   List<Map<String, Integer>> extraCounts) {
        Severity[] leftSev = new Severity[left.size()];
        Severity[] rightSev = new Severity[right.size()];
        Map<Severity, Integer> leftCounts = count(left, leftSev);
        Map<Severity, Integer> rightCounts = count(right, rightSev);

        List<InsightItem> added = new ArrayList<>();
        List<InsightItem> removed = new ArrayList<>();
        InsightItem openAdded = null;
        InsightItem openRemoved = null;
        int firstDivergence = -1;

        for (int i = 0; i < rows.size(); i++) {
            DiffRow row = rows.get(i);
            if (row.isChange() && firstDivergence < 0) {
                firstDivergence = i;
            }
            boolean newProblem = false;
            boolean resolvedProblem = false;
            if (!row.isMoved() && !row.expected()) {
                Severity ls = row.hasLeft() ? leftSev[row.left()] : Severity.NONE;
                Severity rs = row.hasRight() ? rightSev[row.right()] : Severity.NONE;
                if (row.kind() == RowKind.ADDED || row.kind() == RowKind.MODIFIED) {
                    newProblem = rs.isProblem() && (row.kind() == RowKind.ADDED || rs.compareTo(ls) > 0);
                }
                if (row.kind() == RowKind.REMOVED || row.kind() == RowKind.MODIFIED) {
                    resolvedProblem = ls.isProblem() && (row.kind() == RowKind.REMOVED || ls.compareTo(rs) > 0);
                }
                if (newProblem) {
                    openAdded = extend(added, openAdded, i, right.get(row.right()), rs);
                }
                if (resolvedProblem) {
                    openRemoved = extend(removed, openRemoved, i, left.get(row.left()), ls);
                }
            }
            // A group continues only across consecutive problem rows of the same direction.
            if (!newProblem) {
                openAdded = null;
            }
            if (!resolvedProblem) {
                openRemoved = null;
            }
        }
        return new Insights(added, removed, leftCounts, rightCounts, firstDivergence,
                frequencyShifts(left, right, rows, extraCounts));
    }

    private static InsightItem extend(List<InsightItem> list, InsightItem open, int row,
                                      ProcessedLine line, Severity severity) {
        if (open == null) {
            InsightItem item = new InsightItem(row, line.number(), severity, line.text(), 1);
            list.add(item);
            return item;
        }
        Severity max = severity.compareTo(open.severity()) > 0 ? severity : open.severity();
        InsightItem grown = new InsightItem(open.row(), open.lineNumber(), max, open.text(), open.lines() + 1);
        list.removeLast();
        list.add(grown);
        return grown;
    }

    private static Map<Severity, Integer> count(List<ProcessedLine> lines, Severity[] out) {
        Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
        for (int i = 0; i < lines.size(); i++) {
            Severity s = SeverityDetector.detect(lines.get(i).text());
            out[i] = s;
            counts.merge(s, 1, Integer::sum);
        }
        return counts;
    }

    private static List<FrequencyShift> frequencyShifts(List<ProcessedLine> left, List<ProcessedLine> right,
                                                        List<DiffRow> rows, List<Map<String, Integer>> extra) {
        Map<String, int[]> counts = new HashMap<>(); // key -> {left, right}
        Map<String, String> display = new HashMap<>();
        for (ProcessedLine l : left) {
            if (!l.key().isBlank()) {
                counts.computeIfAbsent(l.key(), _ -> new int[2])[0]++;
                display.putIfAbsent(l.key(), l.normalized());
            }
        }
        for (ProcessedLine l : right) {
            if (!l.key().isBlank()) {
                counts.computeIfAbsent(l.key(), _ -> new int[2])[1]++;
                display.putIfAbsent(l.key(), l.normalized());
            }
        }
        List<Map.Entry<String, int[]>> notable = new ArrayList<>();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            int l = e.getValue()[0];
            int r = e.getValue()[1];
            // With several baselines, compare against the nearest end of their range.
            int low = l;
            int high = l;
            for (Map<String, Integer> baselineCounts : extra) {
                int c = baselineCounts.getOrDefault(e.getKey(), 0);
                low = Math.min(low, c);
                high = Math.max(high, c);
            }
            int reference = r > high ? high : r < low ? low : r;
            int min = Math.min(reference, r);
            int max = Math.max(reference, r);
            if (max - min >= MIN_SHIFT && max >= 2 * Math.max(1, min)) {
                notable.add(e);
            }
        }
        notable.sort(Comparator.<Map.Entry<String, int[]>>comparingInt(e -> -Math.abs(e.getValue()[1] - e.getValue()[0]))
                .thenComparing(Map.Entry::getKey));
        if (notable.size() > MAX_SHIFTS) {
            notable = notable.subList(0, MAX_SHIFTS);
        }
        if (notable.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> firstRow = new HashMap<>();
        for (Map.Entry<String, int[]> e : notable) {
            firstRow.put(e.getKey(), -1);
        }
        for (int i = 0; i < rows.size(); i++) {
            DiffRow row = rows.get(i);
            if (row.hasLeft()) {
                markFirst(firstRow, left.get(row.left()).key(), i);
            }
            if (row.hasRight()) {
                markFirst(firstRow, right.get(row.right()).key(), i);
            }
        }
        List<FrequencyShift> out = new ArrayList<>(notable.size());
        for (Map.Entry<String, int[]> e : notable) {
            out.add(new FrequencyShift(display.get(e.getKey()), e.getValue()[0], e.getValue()[1],
                    firstRow.getOrDefault(e.getKey(), -1)));
        }
        return out;
    }

    private static void markFirst(Map<String, Integer> firstRow, String key, int row) {
        Integer current = firstRow.get(key);
        if (current != null && current < 0) {
            firstRow.put(key, row);
        }
    }
}
