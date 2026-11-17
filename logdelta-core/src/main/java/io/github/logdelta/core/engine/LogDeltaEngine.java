// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.engine;

import io.github.logdelta.core.analysis.InsightAnalyzer;
import io.github.logdelta.core.analysis.Insights;
import io.github.logdelta.core.diff.DiffAlgorithm;
import io.github.logdelta.core.diff.Edit;
import io.github.logdelta.core.diff.InlineDiff;
import io.github.logdelta.core.diff.InlineHighlights;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.io.LogReader;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.DiffStats;
import io.github.logdelta.core.model.ProcessedLine;
import io.github.logdelta.core.model.RowKind;
import io.github.logdelta.core.normalize.LineFilter;
import io.github.logdelta.core.normalize.NormalizedText;
import io.github.logdelta.core.normalize.Normalizer;
import io.github.logdelta.core.suggest.RuleSuggester;
import io.github.logdelta.core.suggest.RuleSuggestion;
import io.github.logdelta.core.timing.TimestampParser;
import io.github.logdelta.core.timing.TimingAnalysis;
import io.github.logdelta.core.timing.TimingAnalyzer;
import io.github.logdelta.core.util.CancellationToken;
import io.github.logdelta.core.util.ProgressListener;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/// Entry point of the comparison pipeline.
///
/// ```
/// read ─► filter ignored lines ─► normalize (rules) + parse timestamps ─► intern keys
///      ─► diff (line by line, or entry by entry) ─► build rows (pair modifications)
///      ─► detect moves ─► mark expected variation (extra baselines) ─► inline highlights
///      ─► insights, timing, rule suggestions
/// ```
///
/// The engine is stateless and thread-safe; one instance can serve concurrent comparisons.
///
/// ```java
/// DiffResult r = new LogDeltaEngine().compare(Path.of("good.log"), Path.of("bad.log"), DiffOptions.defaults());
/// r.insights().newProblems().forEach(p -> System.out.println(p.lineNumber() + ": " + p.text()));
/// ```
public final class LogDeltaEngine {

    /// Lines shorter than this (after normalization) are never reported as moved.
    static final int MIN_MOVE_KEY_LENGTH = 8;

    /// Reads both files and compares them.
    public DiffResult compare(Path left, Path right, DiffOptions options) throws IOException {
        return compare(left, right, options, ProgressListener.NONE, CancellationToken.NEVER);
    }

    /// Reads both files and compares them, reporting progress.
    ///
    /// @throws io.github.logdelta.core.util.CancelledException if `token` is cancelled
    public DiffResult compare(Path left, Path right, DiffOptions options, ProgressListener progress,
                              CancellationToken token) throws IOException {
        return compare(List.of(left), right, options, progress, token);
    }

    /// Reads several baselines and a candidate and compares them.
    ///
    /// The first baseline is the one shown side by side. Differences that the other baselines
    /// explain are marked as expected (see [#compare(LogFile, List, LogFile, DiffOptions, ProgressListener,
    /// CancellationToken)]).
    ///
    /// @throws IllegalArgumentException if `baselines` is empty
    public DiffResult compare(List<Path> baselines, Path candidate, DiffOptions options, ProgressListener progress,
                              CancellationToken token) throws IOException {
        if (baselines.isEmpty()) {
            throw new IllegalArgumentException("At least one baseline is required");
        }
        int files = baselines.size() + 1;
        List<LogFile> read = new ArrayList<>();
        for (int i = 0; i < files; i++) {
            Path p = i < baselines.size() ? baselines.get(i) : candidate;
            double from = 0.3 * i / files;
            read.add(LogReader.read(p, null,
                    (_, f) -> progress.onProgress("Reading " + p.getFileName(), from + f * 0.3 / files), token));
        }
        return compare(read.getFirst(), read.subList(1, baselines.size()), read.getLast(), options,
                scaled(progress, 0.30), token);
    }

    /// Compares two logs that are already in memory.
    public DiffResult compare(LogFile left, LogFile right, DiffOptions options) {
        return compare(left, List.of(), right, options, ProgressListener.NONE, CancellationToken.NEVER);
    }

    /// Compares two logs that are already in memory.
    ///
    /// @throws io.github.logdelta.core.util.CancelledException if `token` is cancelled
    public DiffResult compare(LogFile left, LogFile right, DiffOptions options, ProgressListener progress,
                              CancellationToken token) {
        return compare(left, List.of(), right, options, progress, token);
    }

    /// Compares a candidate with a main baseline, using further baselines to recognise normal
    /// variation.
    ///
    /// A difference is marked [DiffRow#expected()] when another good run explains it: an added
    /// or modified candidate line that appears in any extra baseline, or a removed line that is
    /// missing from at least one extra baseline. Expected rows are still shown but are not
    /// reported as new problems and do not make the logs count as different.
    ///
    /// @param left           main baseline, shown side by side
    /// @param extraBaselines further baselines (may be empty)
    /// @param right          candidate
    /// @throws io.github.logdelta.core.util.CancelledException if `token` is cancelled
    public DiffResult compare(LogFile left, List<LogFile> extraBaselines, LogFile right, DiffOptions options,
                              ProgressListener progress, CancellationToken token) {
        long start = System.nanoTime();
        Normalizer normalizer = options.normalizer();
        LineFilter filter = options.lineFilter();

        progress.onProgress("Normalizing", 0.0);
        Processed l = process(left, normalizer, filter, token);
        progress.onProgress("Normalizing", 0.15);
        Processed r = process(right, normalizer, filter, token);
        List<Map<String, Integer>> extraCounts = new ArrayList<>();
        for (LogFile extra : extraBaselines) {
            extraCounts.add(keyCounts(process(extra, normalizer, filter, token).lines()));
        }
        progress.onProgress("Diffing", 0.35);

        List<ProcessedLine> leftLines = l.lines();
        List<ProcessedLine> rightLines = r.lines();
        Map<String, Integer> ids = new HashMap<>();
        int[] a = intern(leftLines, ids);
        int[] b = intern(rightLines, ids);
        DiffAlgorithm algorithm = options.algorithm().create(options.minimal());
        Entries leftEntries = null;
        Entries rightEntries = null;
        List<Edit> edits;
        if (options.multilineEntries()) {
            leftEntries = Entries.of(leftLines, l.times());
            rightEntries = Entries.of(rightLines, r.times());
            edits = Entries.diff(leftLines, leftEntries, a, rightLines, rightEntries, b, algorithm, token);
        } else {
            edits = algorithm.diff(a, b, token);
        }
        progress.onProgress("Building rows", 0.6);

        RowData rows = buildRows(edits, leftLines, rightLines, new LazyWeights(leftLines, rightLines), token);
        if (options.detectMoves()) {
            rows.peers = new int[rows.size()];
            java.util.Arrays.fill(rows.peers, -1);
            if (leftEntries != null) {
                detectEntryMoves(rows, leftLines, leftEntries, rightLines, rightEntries);
            }
            detectMoves(rows, leftLines, rightLines);
        }
        if (!extraCounts.isEmpty()) {
            markExpected(rows, leftLines, rightLines, extraCounts);
        }
        List<DiffRow> diffRows = rows.toRows(leftLines, rightLines, options);
        progress.onProgress("Analyzing", 0.8);

        Insights insights = InsightAnalyzer.analyze(leftLines, rightLines, diffRows, extraCounts);
        TimingAnalysis timing = TimingAnalyzer.analyze(leftLines, rightLines, l.times(), r.times(), diffRows);
        List<RuleSuggestion> suggestions = RuleSuggester.suggest(diffRows, leftLines, rightLines, options.rules(),
                options.ignoreCase(), options.ignoreWhitespace());
        DiffStats stats = stats(left, right, leftLines, rightLines, diffRows);
        progress.onProgress("Done", 1.0);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        return new DiffResult(left, right, leftLines, rightLines, diffRows, stats, insights, options, elapsed,
                extraBaselines, timing, suggestions);
    }

    // ------------------------------------------------------------------------------------------

    /// Below this many lines, normalizing in parallel costs more than it saves.
    static final int PARALLEL_THRESHOLD = 20_000;

    /// Processed lines of one log plus the raw timestamp of each ([TimestampParser#NONE] if absent).
    private record Processed(List<ProcessedLine> lines, long[] times) {
    }

    /// Filters and normalizes every line and parses its timestamp. Lines are independent, so
    /// large files are processed in parallel; the result keeps file order.
    private static Processed process(LogFile file, Normalizer normalizer, LineFilter filter,
                                     CancellationToken token) {
        List<String> lines = file.lines();
        ProcessedLine[] out = new ProcessedLine[lines.size()];
        long[] times = new long[lines.size()];
        IntStream indices = IntStream.range(0, lines.size());
        if (lines.size() >= PARALLEL_THRESHOLD) {
            indices = indices.parallel();
        }
        indices.forEach(i -> {
            if ((i & 1023) == 0) {
                token.throwIfCancelled();
            }
            String line = lines.get(i);
            if (!filter.isIgnored(line)) {
                NormalizedText n = normalizer.normalize(line);
                out[i] = new ProcessedLine(i + 1, line, n.normalized(), n.masks(), normalizer.key(n));
                times[i] = TimestampParser.parse(line);
            }
        });
        token.throwIfCancelled();
        List<ProcessedLine> kept = new ArrayList<>(out.length);
        long[] keptTimes = new long[out.length];
        for (int i = 0; i < out.length; i++) {
            if (out[i] != null) {
                keptTimes[kept.size()] = times[i];
                kept.add(out[i]);
            }
        }
        return new Processed(kept, java.util.Arrays.copyOf(keptTimes, kept.size()));
    }

    private static Map<String, Integer> keyCounts(List<ProcessedLine> lines) {
        Map<String, Integer> counts = new HashMap<>();
        for (ProcessedLine p : lines) {
            counts.merge(p.key(), 1, Integer::sum);
        }
        return counts;
    }

    private static int[] intern(List<ProcessedLine> lines, Map<String, Integer> ids) {
        int[] out = new int[lines.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ids.computeIfAbsent(lines.get(i).key(), _ -> ids.size());
        }
        return out;
    }

    /// Parallel arrays describing rows while they are still being edited.
    private static final class RowData {
        final List<RowKind> kinds = new ArrayList<>();
        final List<int[]> sides = new ArrayList<>(); // {left, right}
        int[] peers;
        boolean[] expected;

        void add(RowKind kind, int left, int right) {
            kinds.add(kind);
            sides.add(new int[] {left, right});
        }

        int size() {
            return kinds.size();
        }

        List<DiffRow> toRows(List<ProcessedLine> left, List<ProcessedLine> right, DiffOptions options) {
            List<DiffRow> out = new ArrayList<>(kinds.size());
            for (int i = 0; i < kinds.size(); i++) {
                RowKind kind = kinds.get(i);
                int l = sides.get(i)[0];
                int r = sides.get(i)[1];
                InlineHighlights inline = InlineHighlights.NONE;
                if (kind == RowKind.MODIFIED && options.inlineDiff()) {
                    ProcessedLine pl = left.get(l);
                    ProcessedLine pr = right.get(r);
                    inline = InlineDiff.compute(pl.text(), pl.masks(), pr.text(), pr.masks(),
                            options.ignoreCase(), options.ignoreWhitespace());
                }
                out.add(new DiffRow(kind, l, r, inline, peers == null ? -1 : peers[i],
                        expected != null && expected[i]));
            }
            return out;
        }
    }

    /// Computes token weights on first use; identical inputs never pay for it.
    private static final class LazyWeights {
        private final List<ProcessedLine> left;
        private final List<ProcessedLine> right;
        private TokenWeights weights;

        LazyWeights(List<ProcessedLine> left, List<ProcessedLine> right) {
            this.left = left;
            this.right = right;
        }

        TokenWeights get() {
            if (weights == null) {
                List<String> keys = new ArrayList<>(left.size() + right.size());
                left.forEach(l -> keys.add(l.key()));
                right.forEach(l -> keys.add(l.key()));
                weights = TokenWeights.of(keys);
            }
            return weights;
        }
    }

    private static RowData buildRows(List<Edit> edits, List<ProcessedLine> left, List<ProcessedLine> right,
                                     LazyWeights weights, CancellationToken token) {
        RowData rows = new RowData();
        List<Integer> removed = new ArrayList<>();
        List<Integer> added = new ArrayList<>();
        for (Edit e : edits) {
            switch (e.type()) {
                case EQUAL -> {
                    flushBlock(rows, removed, added, left, right, weights);
                    for (int k = 0; k < e.aLength(); k++) {
                        rows.add(RowKind.UNCHANGED, e.aStart() + k, e.bStart() + k);
                    }
                }
                case DELETE -> {
                    for (int k = e.aStart(); k < e.aEnd(); k++) {
                        removed.add(k);
                    }
                }
                case INSERT -> {
                    for (int k = e.bStart(); k < e.bEnd(); k++) {
                        added.add(k);
                    }
                }
                default -> throw new IllegalStateException(e.type().toString());
            }
            token.throwIfCancelled();
        }
        flushBlock(rows, removed, added, left, right, weights);
        return rows;
    }

    /// Emits one change block, pairing similar removed/added lines as modifications.
    private static void flushBlock(RowData rows, List<Integer> removed, List<Integer> added,
                                   List<ProcessedLine> left, List<ProcessedLine> right, LazyWeights weights) {
        if (removed.isEmpty() && added.isEmpty()) {
            return;
        }
        List<String> rk = new ArrayList<>(removed.size());
        List<String> ak = new ArrayList<>(added.size());
        removed.forEach(i -> rk.add(left.get(i).key()));
        added.forEach(i -> ak.add(right.get(i).key()));
        int ri = 0;
        int ai = 0;
        List<int[]> pairs = rk.isEmpty() || ak.isEmpty() ? List.of() : LinePairer.pair(rk, ak, weights.get());
        for (int[] p : pairs) {
            while (ri < p[0]) {
                rows.add(RowKind.REMOVED, removed.get(ri++), -1);
            }
            while (ai < p[1]) {
                rows.add(RowKind.ADDED, -1, added.get(ai++));
            }
            boolean same = rk.get(p[0]).equals(ak.get(p[1]));
            rows.add(same ? RowKind.UNCHANGED : RowKind.MODIFIED, removed.get(p[0]), added.get(p[1]));
            ri = p[0] + 1;
            ai = p[1] + 1;
        }
        while (ri < removed.size()) {
            rows.add(RowKind.REMOVED, removed.get(ri++), -1);
        }
        while (ai < added.size()) {
            rows.add(RowKind.ADDED, -1, added.get(ai++));
        }
        removed.clear();
        added.clear();
    }

    /// Links whole removed entries to identical added entries (multi-line mode). Runs before the
    /// line-based pass, which then skips rows that already have a peer.
    private static void detectEntryMoves(RowData rows, List<ProcessedLine> left, Entries le,
                                         List<ProcessedLine> right, Entries re) {
        int[] rowOfLeft = new int[left.size()];
        int[] rowOfRight = new int[right.size()];
        for (int i = 0; i < rows.size(); i++) {
            int[] s = rows.sides.get(i);
            if (s[0] >= 0) {
                rowOfLeft[s[0]] = i;
            }
            if (s[1] >= 0) {
                rowOfRight[s[1]] = i;
            }
        }
        Map<String, Deque<Integer>> removed = new HashMap<>();
        for (int e = 0; e < le.count(); e++) {
            if (le.end(e) - le.start(e) > 1 && allOfKind(rows, rowOfLeft, le.start(e), le.end(e), RowKind.REMOVED)) {
                removed.computeIfAbsent(le.key(left, e), _ -> new ArrayDeque<>()).add(e);
            }
        }
        for (int e = 0; e < re.count() && !removed.isEmpty(); e++) {
            if (re.end(e) - re.start(e) <= 1 || !allOfKind(rows, rowOfRight, re.start(e), re.end(e), RowKind.ADDED)) {
                continue;
            }
            Deque<Integer> candidates = removed.get(re.key(right, e));
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }
            int from = candidates.poll();
            for (int k = 0; k < re.end(e) - re.start(e); k++) {
                int ra = rowOfLeft[le.start(from) + k];
                int rb = rowOfRight[re.start(e) + k];
                rows.peers[ra] = rb;
                rows.peers[rb] = ra;
            }
        }
    }

    private static boolean allOfKind(RowData rows, int[] rowOf, int from, int to, RowKind kind) {
        for (int i = from; i < to; i++) {
            if (rows.kinds.get(rowOf[i]) != kind) {
                return false;
            }
        }
        return true;
    }

    /// Links removed lines to identical added lines elsewhere in the file.
    private static void detectMoves(RowData rows, List<ProcessedLine> left, List<ProcessedLine> right) {
        int[] peers = rows.peers;
        Map<String, Deque<Integer>> removedByKey = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.kinds.get(i) == RowKind.REMOVED && peers[i] < 0) {
                String key = left.get(rows.sides.get(i)[0]).key();
                if (key.strip().length() >= MIN_MOVE_KEY_LENGTH) {
                    removedByKey.computeIfAbsent(key, _ -> new ArrayDeque<>()).add(i);
                }
            }
        }
        if (removedByKey.isEmpty()) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.kinds.get(i) == RowKind.ADDED && peers[i] < 0) {
                Deque<Integer> candidates = removedByKey.get(right.get(rows.sides.get(i)[1]).key());
                if (candidates != null && !candidates.isEmpty()) {
                    int peer = candidates.poll();
                    peers[i] = peer;
                    peers[peer] = i;
                }
            }
        }
    }

    /// Marks differences that another baseline explains (see [DiffRow#expected()]).
    private static void markExpected(RowData rows, List<ProcessedLine> left, List<ProcessedLine> right,
                                     List<Map<String, Integer>> extraCounts) {
        rows.expected = new boolean[rows.size()];
        List<Set<String>> sets = new ArrayList<>();
        extraCounts.forEach(c -> sets.add(new HashSet<>(c.keySet())));
        for (int i = 0; i < rows.size(); i++) {
            if (rows.peers != null && rows.peers[i] >= 0) {
                continue; // moves are handled separately
            }
            int[] s = rows.sides.get(i);
            rows.expected[i] = switch (rows.kinds.get(i)) {
                case ADDED, MODIFIED -> {
                    String key = right.get(s[1]).key();
                    yield sets.stream().anyMatch(set -> set.contains(key));
                }
                case REMOVED -> {
                    String key = left.get(s[0]).key();
                    yield sets.stream().anyMatch(set -> !set.contains(key));
                }
                case UNCHANGED -> false;
            };
        }
    }

    private static DiffStats stats(LogFile left, LogFile right, List<ProcessedLine> leftLines,
                                   List<ProcessedLine> rightLines, List<DiffRow> rows) {
        int unchanged = 0;
        int modified = 0;
        int removed = 0;
        int added = 0;
        int moved = 0;
        int expected = 0;
        for (DiffRow r : rows) {
            if (r.isChange() && r.expected()) {
                expected++;
            }
            switch (r.kind()) {
                case UNCHANGED -> unchanged++;
                case MODIFIED -> modified++;
                case REMOVED -> removed++;
                case ADDED -> {
                    added++;
                    if (r.isMoved()) {
                        moved++;
                    }
                }
                default -> throw new IllegalStateException();
            }
        }
        int leftMasked = (int) leftLines.stream().filter(ProcessedLine::isMasked).count();
        int rightMasked = (int) rightLines.stream().filter(ProcessedLine::isMasked).count();
        return new DiffStats(left.lineCount(), right.lineCount(), left.lineCount() - leftLines.size(),
                right.lineCount() - rightLines.size(), unchanged, modified, removed, added, moved,
                leftMasked, rightMasked, expected);
    }

    private static ProgressListener scaled(ProgressListener p, double from) {
        return (stage, f) -> p.onProgress(stage, f < 0 ? f : from + f * (1 - from));
    }
}
