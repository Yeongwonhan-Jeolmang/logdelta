// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.engine;

import io.github.logdelta.core.analysis.FlakyLine;
import io.github.logdelta.core.analysis.InsightAnalyzer;
import io.github.logdelta.core.analysis.Insights;
import io.github.logdelta.core.analysis.ProblemTimeline;
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
import io.github.logdelta.core.normalize.Rule;
import io.github.logdelta.core.normalize.RuleSet;
import io.github.logdelta.core.preset.Preset;
import io.github.logdelta.core.preset.Presets;
import io.github.logdelta.core.structured.Field;
import io.github.logdelta.core.structured.FieldFilter;
import io.github.logdelta.core.structured.LogFormat;
import io.github.logdelta.core.structured.StructuredLines;
import io.github.logdelta.core.structured.StructuredParser;
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
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/// Entry point of the comparison pipeline.
///
/// ```
/// read (time window) ─► detect format (JSON/logfmt) and preset ─► filter ignored lines
///      ─► normalize (rules; fields for structured lines) + parse timestamps ─► intern keys
///      ─► diff (line by line, or entry by entry) ─► build rows (pair modifications)
///      ─► detect moves ─► mark expected variation (extra baselines) ─► inline highlights
///      ─► insights (templates, flaky lines), timing, problem timeline, rule suggestions
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
                    (_, f) -> progress.onProgress("Reading " + p.getFileName(), from + f * 0.3 / files), token,
                    options.timeWindow()));
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
        LogFile leftFile = left.window(options.timeWindow());
        LogFile rightFile = right.window(options.timeWindow());
        List<LogFile> extraFiles = new ArrayList<>();
        for (LogFile e : extraBaselines) {
            extraFiles.add(e.window(options.timeWindow()));
        }

        Detection detection = detect(leftFile, rightFile, options);
        DiffOptions effective = options;
        if (detection.preset() != null) {
            effective = options.toBuilder().rules(Presets.apply(detection.preset(), options.rules())).build();
        }
        Normalizer normalizer = effective.normalizer();
        LineFilter filter = effective.lineFilter();
        Context ctx = new Context(normalizer, filter, detection.format(), effective.fieldFilter(), token);

        progress.onProgress("Normalizing", 0.0);
        Processed l = process(leftFile, ctx);
        progress.onProgress("Normalizing", 0.15);
        Processed r = process(rightFile, ctx);
        List<Processed> extras = new ArrayList<>();
        List<Map<String, Integer>> extraCounts = new ArrayList<>();
        for (LogFile extra : extraFiles) {
            Processed p = process(extra, ctx);
            extras.add(p);
            extraCounts.add(keyCounts(p.lines()));
        }
        progress.onProgress("Diffing", 0.35);

        Map<String, Integer> ids = new HashMap<>();
        Map<String, String> canonical = new HashMap<>();
        List<ProcessedLine> leftLines = deduplicate(l.lines(), canonical);
        List<ProcessedLine> rightLines = deduplicate(r.lines(), canonical);
        canonical = null; // let the map go before diffing
        int[] a = intern(leftLines, ids);
        int[] b = intern(rightLines, ids);
        DiffAlgorithm algorithm = effective.algorithm().create(effective.minimal());
        Entries leftEntries = null;
        Entries rightEntries = null;
        List<Edit> edits;
        if (effective.multilineEntries()) {
            leftEntries = Entries.of(leftLines, l.times());
            rightEntries = Entries.of(rightLines, r.times());
            edits = Entries.diff(leftLines, leftEntries, a, rightLines, rightEntries, b, algorithm, token);
        } else {
            edits = algorithm.diff(a, b, token);
        }
        progress.onProgress("Building rows", 0.6);

        RowData rows = buildRows(edits, leftLines, rightLines, new LazyWeights(leftLines, rightLines), token);
        if (effective.detectMoves()) {
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
        List<DiffRow> diffRows = rows.toRows(leftLines, rightLines, effective, normalizer);
        progress.onProgress("Analyzing", 0.8);

        List<List<ProcessedLine>> extraLines = extras.stream().map(Processed::lines).toList();
        Insights insights = InsightAnalyzer.analyze(leftLines, rightLines, diffRows, extraLines);
        if (!extras.isEmpty()) {
            insights = insights.withFlakyLines(flakyLines(leftLines, extraLines, extraFiles, effective, algorithm,
                    token));
        }
        TimingAnalysis timing = TimingAnalyzer.analyze(leftLines, rightLines, l.times(), r.times(), diffRows);
        ProblemTimeline problems = ProblemTimeline.of(leftLines, rightLines, timing.left(), timing.right(), diffRows);
        List<RuleSuggestion> suggestions = RuleSuggester.suggest(diffRows, leftLines, rightLines, effective.rules(),
                effective.ignoreCase(), effective.ignoreWhitespace());
        DiffStats stats = stats(leftFile, rightFile, leftLines, rightLines, diffRows);
        progress.onProgress("Done", 1.0);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        return new DiffResult(leftFile, rightFile, leftLines, rightLines, diffRows, stats, insights, effective,
                elapsed, extraFiles, timing, suggestions, detection, problems);
    }

    /// One log as a comparison sees it.
    ///
    /// @param lines     the kept lines, normalized (ignored lines are left out)
    /// @param detection the format and preset used
    /// @param options   the options with the preset's rules added
    public record Prepared(List<ProcessedLine> lines, Detection detection, DiffOptions options) {
    }

    /// Prepares one log exactly as [#compare] would: time window, format and preset detection,
    /// ignore patterns, structured fields and rules. Used by `logdelta normalize` and `explain`,
    /// so that what they print is what a comparison compares.
    public static Prepared prepare(LogFile file, DiffOptions options) {
        LogFile windowed = file.window(options.timeWindow());
        Detection detection = detect(windowed, windowed, options);
        DiffOptions effective = options;
        if (detection.preset() != null) {
            effective = options.toBuilder().rules(Presets.apply(detection.preset(), options.rules())).build();
        }
        Context ctx = new Context(effective.normalizer(), effective.lineFilter(), detection.format(),
                effective.fieldFilter(), CancellationToken.NEVER);
        return new Prepared(process(windowed, ctx).lines(), detection, effective);
    }

    /// Decides the log format and preset for a comparison.
    static Detection detect(LogFile left, LogFile right, DiffOptions options) {
        List<List<String>> sample = List.of(left.lines(), right.lines());
        LogFormat format = options.format() == LogFormat.AUTO ? StructuredParser.detect(sample) : options.format();
        Preset preset = null;
        String p = options.preset();
        if (p.equals(Presets.AUTO)) {
            if (!format.isStructured()) {
                preset = Presets.detect(sample);
            }
        } else if (!p.equals(Presets.NONE)) {
            preset = Presets.find(p);
        }
        return new Detection(format, preset, options.format() == LogFormat.AUTO, p.equals(Presets.AUTO));
    }

    // ------------------------------------------------------------------------------------------

    /// Below this many lines, normalizing in parallel costs more than it saves.
    static final int PARALLEL_THRESHOLD = 20_000;

    /// Processed lines of one log plus the raw timestamp of each ([TimestampParser#NONE] if absent).
    private record Processed(List<ProcessedLine> lines, long[] times) {
    }

    /// What [#process] needs, shared by all logs of one comparison.
    private record Context(Normalizer normalizer, LineFilter filter, LogFormat format, FieldFilter fields,
                           CancellationToken token) {
    }

    /// Filters and normalizes every line and parses its timestamp. Lines are independent, so
    /// large files are processed in parallel; the result keeps file order.
    private static Processed process(LogFile file, Context ctx) {
        List<String> lines = file.lines();
        ProcessedLine[] out = new ProcessedLine[lines.size()];
        long[] times = new long[lines.size()];
        IntStream indices = IntStream.range(0, lines.size());
        if (lines.size() >= PARALLEL_THRESHOLD) {
            indices = indices.parallel();
        }
        Normalizer normalizer = ctx.normalizer();
        indices.forEach(i -> {
            if ((i & 1023) == 0) {
                ctx.token().throwIfCancelled();
            }
            String line = lines.get(i);
            if (!ctx.filter().isIgnored(line)) {
                NormalizedText n = normalizer.normalize(line);
                int number = file.lineNumber(i);
                List<Field> fields = ctx.format().isStructured() ? StructuredParser.parse(line, ctx.format()) : null;
                ProcessedLine p;
                if (fields != null && !fields.isEmpty()) {
                    p = StructuredLines.process(number, line, fields, n, ctx.fields(), normalizer);
                    long t = StructuredLines.time(p);
                    times[i] = t != TimestampParser.NONE ? t : TimestampParser.parse(line);
                } else {
                    p = new ProcessedLine(number, line, n.normalized(), n.masks(), normalizer.key(n));
                    times[i] = TimestampParser.parse(line);
                }
                out[i] = p;
            }
        });
        ctx.token().throwIfCancelled();
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

    /// Makes lines with equal normalized text share one string. Logs are repetitive once
    /// timestamps and ids are masked, so for large files this saves a lot of memory: each distinct
    /// normalized line is held once instead of once per occurrence.
    private static List<ProcessedLine> deduplicate(List<ProcessedLine> lines, Map<String, String> canonical) {
        if (lines.size() < PARALLEL_THRESHOLD) {
            return lines;
        }
        List<ProcessedLine> out = new ArrayList<>(lines.size());
        for (ProcessedLine p : lines) {
            if (p.normalized() == p.text()) {
                out.add(p); // unmasked: the normalized text is the original line itself
                continue;
            }
            String n = canonical.putIfAbsent(p.normalized(), p.normalized());
            if (n == null || n == p.normalized()) {
                out.add(p);
            } else {
                String key = p.key() == p.normalized() ? n : p.key();
                out.add(new ProcessedLine(p.number(), p.text(), n, p.masks(), key, p.fields()));
            }
        }
        return out;
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
        private static final RowKind[] KINDS = RowKind.values();
        /// Compact storage: millions of rows are common with large logs.
        private byte[] kindCodes = new byte[64];
        private int[] lefts = new int[64];
        private int[] rights = new int[64];
        private int size;
        int[] peers;
        boolean[] expected;

        void add(RowKind kind, int left, int right) {
            if (size == lefts.length) {
                int cap = lefts.length * 2;
                kindCodes = java.util.Arrays.copyOf(kindCodes, cap);
                lefts = java.util.Arrays.copyOf(lefts, cap);
                rights = java.util.Arrays.copyOf(rights, cap);
            }
            kindCodes[size] = (byte) kind.ordinal();
            lefts[size] = left;
            rights[size] = right;
            size++;
        }

        int size() {
            return size;
        }

        RowKind kind(int i) {
            return KINDS[kindCodes[i]];
        }

        int left(int i) {
            return lefts[i];
        }

        int right(int i) {
            return rights[i];
        }

        List<DiffRow> toRows(List<ProcessedLine> left, List<ProcessedLine> right, DiffOptions options,
                             Normalizer normalizer) {
            List<DiffRow> out = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                RowKind kind = kind(i);
                int l = lefts[i];
                int r = rights[i];
                InlineHighlights inline = InlineHighlights.NONE;
                if (kind == RowKind.MODIFIED && options.inlineDiff()) {
                    ProcessedLine pl = left.get(l);
                    ProcessedLine pr = right.get(r);
                    if (pl.isStructured() && pr.isStructured()) {
                        inline = StructuredLines.highlights(pl, pr, normalizer);
                    } else {
                        inline = InlineDiff.compute(pl.text(), pl.masks(), pr.text(), pr.masks(),
                                options.ignoreCase(), options.ignoreWhitespace());
                    }
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

        /// Token rarity is estimated from at most this many lines, spread over both logs.
        static final int MAX_SAMPLE = 200_000;

        TokenWeights get() {
            if (weights == null) {
                int total = left.size() + right.size();
                int step = Math.max(1, total / MAX_SAMPLE);
                List<String> keys = new ArrayList<>(Math.min(total, MAX_SAMPLE + 2));
                for (int i = 0; i < left.size(); i += step) {
                    keys.add(left.get(i).key());
                }
                for (int i = 0; i < right.size(); i += step) {
                    keys.add(right.get(i).key());
                }
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
            if (rows.left(i) >= 0) {
                rowOfLeft[rows.left(i)] = i;
            }
            if (rows.right(i) >= 0) {
                rowOfRight[rows.right(i)] = i;
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
            if (rows.kind(rowOf[i]) != kind) {
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
            if (rows.kind(i) == RowKind.REMOVED && peers[i] < 0) {
                String key = left.get(rows.left(i)).key();
                if (key.strip().length() >= MIN_MOVE_KEY_LENGTH) {
                    removedByKey.computeIfAbsent(key, _ -> new ArrayDeque<>()).add(i);
                }
            }
        }
        if (removedByKey.isEmpty()) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.kind(i) == RowKind.ADDED && peers[i] < 0) {
                Deque<Integer> candidates = removedByKey.get(right.get(rows.right(i)).key());
                if (candidates != null && !candidates.isEmpty()) {
                    int peer = candidates.poll();
                    peers[i] = peer;
                    peers[peer] = i;
                }
            }
        }
    }

    /// Most flaky lines reported.
    static final int MAX_FLAKY = 20;
    private static final int MIN_IGNORE_LITERAL = 8;

    /// Compares the main baseline with every extra baseline and reports the lines that differ
    /// between these good runs, each with a rule or ignore pattern that would silence it.
    private static List<FlakyLine> flakyLines(List<ProcessedLine> main, List<List<ProcessedLine>> extras,
                                              List<LogFile> extraFiles, DiffOptions options,
                                              DiffAlgorithm algorithm, CancellationToken token) {
        int runs = extras.size() + 1;
        // per main line: {modified in how many runs, missing in how many runs}
        int[] modified = new int[main.size()];
        int[] missing = new int[main.size()];
        String[] example = new String[main.size()];
        String[] exampleNormalized = new String[main.size()];
        // lines that only some extra runs have: key -> {count, extra index, line index}
        Map<String, int[]> onlyInExtras = new java.util.LinkedHashMap<>();
        for (int e = 0; e < extras.size(); e++) {
            List<ProcessedLine> other = extras.get(e);
            Map<String, Integer> ids = new HashMap<>();
            int[] a = intern(main, ids);
            int[] b = intern(other, ids);
            RowData rows = buildRows(algorithm.diff(a, b, token), main, other, new LazyWeights(main, other), token);
            Set<String> seenAdded = new HashSet<>();
            for (int i = 0; i < rows.size(); i++) {
                int[] sides = {rows.left(i), rows.right(i)};
                switch (rows.kind(i)) {
                    case MODIFIED -> {
                        modified[sides[0]]++;
                        if (example[sides[0]] == null) {
                            example[sides[0]] = other.get(sides[1]).text();
                            exampleNormalized[sides[0]] = other.get(sides[1]).normalized();
                        }
                    }
                    case REMOVED -> missing[sides[0]]++;
                    case ADDED -> {
                        String key = other.get(sides[1]).key();
                        if (seenAdded.add(key)) {
                            int[] c = onlyInExtras.computeIfAbsent(key, _ -> new int[] {0, -1, -1});
                            c[0]++;
                            if (c[1] < 0) {
                                c[1] = e;
                                c[2] = sides[1];
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        RuleSet rules = options.rules();
        Set<String> takenIds = new HashSet<>();
        rules.forEach(r -> takenIds.add(r.id()));
        Set<String> reportedKeys = new HashSet<>();
        // fix (rule patterns or ignore pattern, or the line's shape) -> index in out
        Map<String, Integer> byFix = new HashMap<>();
        List<FlakyLine> out = new ArrayList<>();
        // Lines that change most often first; ties in file order.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < main.size(); i++) {
            if (modified[i] > 0 || missing[i] > 0) {
                order.add(i);
            }
        }
        order.sort(java.util.Comparator.comparingInt((Integer i) -> -(modified[i] + missing[i])));
        for (int i : order) {
            ProcessedLine line = main.get(i);
            if (line.key().isBlank() || !reportedKeys.add(line.key())) {
                continue;
            }
            FlakyLine flaky;
            String fixKey;
            if (modified[i] >= missing[i]) {
                List<Rule> fix = RuleSuggester.rulesFor(line.text(), line.normalized(), example[i],
                        exampleNormalized[i], rules, options.ignoreCase(), options.ignoreWhitespace(), takenIds);
                String pattern = fix.isEmpty() ? ignorePattern(line) : null;
                fixKey = !fix.isEmpty() ? "rules:" + fix.stream().map(Rule::regex).toList() : "shape:" + shape(line);
                flaky = new FlakyLine(FlakyLine.Kind.VALUES, line.number(), line.text(), modified[i], runs, fix,
                        pattern, example[i], 0);
            } else {
                fixKey = "shape:" + shape(line);
                flaky = new FlakyLine(FlakyLine.Kind.PRESENCE, line.number(), line.text(), missing[i], runs, List.of(),
                        ignorePattern(line), null, 0);
            }
            Integer at = byFix.get(fixKey);
            if (at != null) {
                out.set(at, out.get(at).withSimilarLines(out.get(at).similarLines() + 1));
            } else if (out.size() < MAX_FLAKY) {
                byFix.put(fixKey, out.size());
                out.add(flaky);
            }
        }
        for (Map.Entry<String, int[]> e : onlyInExtras.entrySet()) {
            int[] c = e.getValue();
            ProcessedLine line = extras.get(c[1]).get(c[2]);
            if (line.key().isBlank() || !reportedKeys.add(line.key())) {
                continue;
            }
            String fixKey = "shape:" + shape(line);
            Integer at = byFix.get(fixKey);
            if (at != null) {
                out.set(at, out.get(at).withSimilarLines(out.get(at).similarLines() + 1));
            } else if (out.size() < MAX_FLAKY) {
                byFix.put(fixKey, out.size());
                out.add(new FlakyLine(FlakyLine.Kind.PRESENCE, line.number(), line.text() + "  (in "
                        + extraFiles.get(c[1]).displayName() + ")", c[0], runs, List.of(), ignorePattern(line), null, 0));
            }
        }
        return out;
    }

    /// The normalized line with every number replaced, to group lines that differ only in numbers.
    private static String shape(ProcessedLine line) {
        return DIGITS.matcher(line.normalized()).replaceAll("#");
    }

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /// A regular expression matching `line` by its longest stretch of unmasked text, or `null` if
    /// that stretch is too short to be specific.
    static String ignorePattern(ProcessedLine line) {
        String text = line.text();
        int bestStart = 0;
        int bestEnd = 0;
        int pos = 0;
        for (var m : line.masks()) {
            if (m.start() - pos > bestEnd - bestStart) {
                bestStart = pos;
                bestEnd = m.start();
            }
            pos = Math.max(pos, m.end());
        }
        if (text.length() - pos > bestEnd - bestStart) {
            bestStart = pos;
            bestEnd = text.length();
        }
        String literal = text.substring(bestStart, bestEnd).strip();
        if (literal.length() < MIN_IGNORE_LITERAL) {
            return null;
        }
        return Pattern.quote(literal);
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
            int[] s = {rows.left(i), rows.right(i)};
            rows.expected[i] = switch (rows.kind(i)) {
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
