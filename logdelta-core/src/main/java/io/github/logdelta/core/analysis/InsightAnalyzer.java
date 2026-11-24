// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.analysis;

import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.ProcessedLine;
import io.github.logdelta.core.model.RowKind;
import io.github.logdelta.core.structured.StructuredLines;
import io.github.logdelta.core.util.LogText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /// New (and resolved) problems that share a [message template][TemplateMiner] are reported
    /// once, with the number of [occurrences][InsightItem#occurrences()].
    ///
    /// @param left   processed lines of the main baseline
    /// @param right  processed candidate lines
    /// @param rows   rows referencing `left` and `right`
    /// @param extras processed lines of every additional baseline
    public static Insights analyze(List<ProcessedLine> left, List<ProcessedLine> right, List<DiffRow> rows,
                                   List<List<ProcessedLine>> extras) {
        Severity[] leftSev = new Severity[left.size()];
        Severity[] rightSev = new Severity[right.size()];
        Map<Severity, Integer> leftCounts = count(left, leftSev);
        Map<Severity, Integer> rightCounts = count(right, rightSev);
        List<Map<String, Integer>> extraCounts = new ArrayList<>();
        for (List<ProcessedLine> e : extras) {
            Map<String, Integer> c = new HashMap<>();
            e.forEach(p -> c.merge(p.key(), 1, Integer::sum));
            extraCounts.add(c);
        }
        Templates templates = Templates.mine(left, leftSev, right, rightSev, extras);

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
        List<InsightItem> newProblems = collapse(added, rows, templates.right, templates);
        List<InsightItem> resolvedProblems = collapse(removed, rows, templates.left, templates);
        List<TemplateShift> templateShifts = templateShifts(left, right, rows, templates, leftSev, rightSev);
        List<FrequencyShift> shifts = frequencyShifts(left, right, rows, extraCounts);
        if (!templateShifts.isEmpty()) {
            // A message that a reported template covers is listed once, as part of the template.
            Set<String> covered = new HashSet<>();
            templateShifts.forEach(t -> covered.add(t.template()));
            Map<String, String> templateOfDisplay = new HashMap<>();
            for (int i = 0; i < left.size(); i++) {
                templateOfDisplay.putIfAbsent(left.get(i).normalized(), templates.miner.text(templates.left[i]));
            }
            for (int i = 0; i < right.size(); i++) {
                templateOfDisplay.putIfAbsent(right.get(i).normalized(), templates.miner.text(templates.right[i]));
            }
            shifts = shifts.stream().filter(f -> !covered.contains(templateOfDisplay.get(f.template()))).toList();
        }
        return new Insights(newProblems, resolvedProblems, leftCounts, rightCounts, firstDivergence, shifts,
                templateShifts, List.of());
    }

    /// Whether a template says enough to be worth reporting: at least two words that are neither
    /// variables nor masked values (so `at <*>`, a stack frame, is not reported).
    static boolean isSpecific(String template) {
        int literal = 0;
        for (String token : template.split("\\s+")) {
            if (!token.isEmpty() && !token.equals(TemplateMiner.WILDCARD)
                    && !(token.startsWith("<") && token.endsWith(">")) && token.chars().anyMatch(Character::isLetter)) {
                literal++;
            }
        }
        return literal >= 2;
    }

    /// Reports problem groups whose first line shares a template only once.
    private static List<InsightItem> collapse(List<InsightItem> items, List<DiffRow> rows, int[] lineTemplates,
                                              Templates templates) {
        if (items.size() < 2) {
            return items;
        }
        Map<Integer, Integer> indexByTemplate = new HashMap<>();
        List<InsightItem> out = new ArrayList<>();
        for (InsightItem item : items) {
            DiffRow row = rows.get(item.row());
            int line = lineTemplates == templates.right ? row.right() : row.left();
            int t = line >= 0 ? lineTemplates[line] : -1;
            Integer at = t < 0 ? null : indexByTemplate.get(t);
            if (at == null) {
                if (t >= 0) {
                    indexByTemplate.put(t, out.size());
                }
                out.add(item);
            } else {
                InsightItem first = out.get(at);
                Severity max = item.severity().compareTo(first.severity()) > 0 ? item.severity() : first.severity();
                out.set(at, new InsightItem(first.row(), first.lineNumber(), max, first.text(), first.lines(),
                        first.occurrences() + 1, templates.miner.text(t)));
            }
        }
        return out;
    }

    /// Message templates of both logs (and the extra baselines).
    private static final class Templates {
        final TemplateMiner miner = new TemplateMiner();
        int[] left;
        int[] right;
        List<int[]> extras = new ArrayList<>();

        static Templates mine(List<ProcessedLine> left, Severity[] leftSev, List<ProcessedLine> right,
                              Severity[] rightSev, List<List<ProcessedLine>> extras) {
            Templates t = new Templates();
            int[] li = t.add(left, leftSev);
            int[] ri = t.add(right, rightSev);
            List<int[]> ei = new ArrayList<>();
            for (List<ProcessedLine> e : extras) {
                Severity[] sev = new Severity[e.size()];
                for (int i = 0; i < sev.length; i++) {
                    sev[i] = SeverityDetector.detect(e.get(i));
                }
                ei.add(t.add(e, sev));
            }
            t.miner.finish();
            t.left = resolve(t.miner, li);
            t.right = resolve(t.miner, ri);
            for (int[] e : ei) {
                t.extras.add(resolve(t.miner, e));
            }
            return t;
        }

        private int[] add(List<ProcessedLine> lines, Severity[] sev) {
            int[] idx = new int[lines.size()];
            for (int i = 0; i < idx.length; i++) {
                idx[i] = miner.add(sev[i].name(), messageOf(lines.get(i)));
            }
            return idx;
        }

        private static int[] resolve(TemplateMiner miner, int[] indices) {
            int[] out = new int[indices.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = miner.templateOf(indices[i]);
            }
            return out;
        }
    }

    /// The text a line contributes to template mining: the message field of a structured line,
    /// otherwise the normalized line without its timestamp/level/logger prefix.
    static String messageOf(ProcessedLine line) {
        if (line.isStructured()) {
            String m = StructuredLines.message(line, true);
            if (m != null) {
                return m;
            }
        }
        return LogText.message(line.normalized());
    }

    private static List<TemplateShift> templateShifts(List<ProcessedLine> left, List<ProcessedLine> right,
                                                      List<DiffRow> rows, Templates t, Severity[] leftSev,
                                                      Severity[] rightSev) {
        Map<Integer, int[]> counts = new LinkedHashMap<>(); // template -> {left, right}
        Map<Integer, Set<String>> variants = new HashMap<>();
        Map<Integer, Severity> severity = new HashMap<>();
        for (int i = 0; i < left.size(); i++) {
            int id = t.left[i];
            counts.computeIfAbsent(id, _ -> new int[2])[0]++;
            addVariant(variants, id, left.get(i).key());
            severity.putIfAbsent(id, leftSev[i]);
        }
        for (int i = 0; i < right.size(); i++) {
            int id = t.right[i];
            counts.computeIfAbsent(id, _ -> new int[2])[1]++;
            addVariant(variants, id, right.get(i).key());
            severity.putIfAbsent(id, rightSev[i]);
        }
        List<Map<Integer, Integer>> extraCounts = new ArrayList<>();
        for (int[] e : t.extras) {
            Map<Integer, Integer> c = new HashMap<>();
            for (int id : e) {
                c.merge(id, 1, Integer::sum);
            }
            extraCounts.add(c);
        }
        List<Integer> notable = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : counts.entrySet()) {
            int id = e.getKey();
            if (variants.get(id).size() < 2 || !isSpecific(t.miner.text(id))) {
                continue; // a single message is already covered by frequency shifts
            }
            int l = e.getValue()[0];
            int r = e.getValue()[1];
            int low = l;
            int high = l;
            for (Map<Integer, Integer> c : extraCounts) {
                int v = c.getOrDefault(id, 0);
                low = Math.min(low, v);
                high = Math.max(high, v);
            }
            int reference = r > high ? high : r < low ? low : r;
            if (isNotable(reference, r)) {
                notable.add(id);
            }
        }
        notable.sort(Comparator.<Integer>comparingInt(id -> -Math.abs(counts.get(id)[1] - counts.get(id)[0]))
                .thenComparing(id -> t.miner.text(id)));
        if (notable.size() > MAX_SHIFTS) {
            notable = notable.subList(0, MAX_SHIFTS);
        }
        if (notable.isEmpty()) {
            return List.of();
        }
        Map<Integer, Integer> firstRow = new HashMap<>();
        for (int i = 0; i < rows.size() && firstRow.size() < notable.size(); i++) {
            DiffRow row = rows.get(i);
            if (row.hasLeft() && notable.contains(t.left[row.left()])) {
                firstRow.putIfAbsent(t.left[row.left()], i);
            }
            if (row.hasRight() && notable.contains(t.right[row.right()])) {
                firstRow.putIfAbsent(t.right[row.right()], i);
            }
        }
        List<TemplateShift> out = new ArrayList<>();
        for (int id : notable) {
            int[] c = counts.get(id);
            out.add(new TemplateShift(t.miner.text(id), c[0], c[1], variants.get(id).size(),
                    firstRow.getOrDefault(id, -1), severity.getOrDefault(id, Severity.NONE)));
        }
        return out;
    }

    /// Distinct messages counted per template; beyond this the exact number does not matter.
    static final int MAX_VARIANTS = 1000;

    private static void addVariant(Map<Integer, Set<String>> variants, int id, String key) {
        Set<String> set = variants.computeIfAbsent(id, _ -> new HashSet<>());
        if (set.size() < MAX_VARIANTS) {
            set.add(key);
        }
    }

    private static boolean isNotable(int reference, int count) {
        int min = Math.min(reference, count);
        int max = Math.max(reference, count);
        return max - min >= MIN_SHIFT && max >= 2 * Math.max(1, min);
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
            Severity s = SeverityDetector.detect(lines.get(i));
            out[i] = s;
            counts.merge(s, 1, Integer::sum);
        }
        return counts;
    }

    /// Occurrences of one normalized message in both logs.
    private static final class Count {
        final String display;
        int left;
        int right;

        Count(String display) {
            this.display = display;
        }
    }

    private static List<FrequencyShift> frequencyShifts(List<ProcessedLine> left, List<ProcessedLine> right,
                                                        List<DiffRow> rows, List<Map<String, Integer>> extra) {
        Map<String, Count> byKey = new HashMap<>();
        for (ProcessedLine l : left) {
            if (!l.key().isBlank()) {
                byKey.computeIfAbsent(l.key(), _ -> new Count(l.normalized())).left++;
            }
        }
        for (ProcessedLine l : right) {
            if (!l.key().isBlank()) {
                byKey.computeIfAbsent(l.key(), _ -> new Count(l.normalized())).right++;
            }
        }
        Map<String, int[]> counts = new HashMap<>();
        Map<String, String> display = new HashMap<>();
        for (Map.Entry<String, Count> e : byKey.entrySet()) {
            int l = e.getValue().left;
            int r = e.getValue().right;
            // A message seen fewer than MIN_SHIFT times on both sides can never shift notably, with
            // or without further baselines; dropping it early keeps this cheap for huge logs.
            if (Math.max(l, r) >= MIN_SHIFT) {
                counts.put(e.getKey(), new int[] {l, r});
                display.put(e.getKey(), e.getValue().display);
            }
        }
        byKey = null;
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
            if (isNotable(reference, r)) {
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
