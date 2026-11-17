// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.engine;

import io.github.logdelta.core.diff.DiffAlgorithm;
import io.github.logdelta.core.diff.Edit;
import io.github.logdelta.core.model.ProcessedLine;
import io.github.logdelta.core.timing.TimestampParser;
import io.github.logdelta.core.util.CancellationToken;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/// Multi-line log entries: a line plus its continuation lines, such as the stack trace that
/// follows an error message.
///
/// When most lines of a log carry a timestamp, every line **without** one continues the entry
/// above it (this catches `java.net.SocketTimeoutException: ...` lines, which have no indent).
/// Otherwise a line continues the entry above when it starts with whitespace, `Caused by:` or
/// `... 12 more`.
///
/// [#diff] compares logs entry by entry, so an entry only matches another entry when all of
/// its lines match, and a moved exception moves as a whole. Inside a changed region the lines
/// are then compared one by one, so the display stays line-based.
final class Entries {

    private static final Pattern CONTINUATION = Pattern.compile("^(?:\\s|Caused by:|\\.\\.\\. \\d+ (?:more|common))");
    private static final double TIMESTAMP_COVERAGE = 0.3;

    /// Start index of every entry plus a final element equal to the number of lines.
    final int[] bounds;
    /// Entry index of every line.
    final int[] entryOfLine;

    private Entries(int[] bounds, int lines) {
        this.bounds = bounds;
        this.entryOfLine = new int[lines];
        for (int e = 0; e + 1 < bounds.length; e++) {
            Arrays.fill(entryOfLine, bounds[e], bounds[e + 1], e);
        }
    }

    /// Groups lines into entries; `times` are raw [TimestampParser] values per line.
    static Entries of(List<ProcessedLine> lines, long[] times) {
        int timed = 0;
        for (long t : times) {
            if (t != TimestampParser.NONE) {
                timed++;
            }
        }
        boolean useTimes = timed >= 2 && timed >= TIMESTAMP_COVERAGE * lines.size();
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            boolean continuation = i > 0 && (useTimes ? times[i] == TimestampParser.NONE
                    : CONTINUATION.matcher(lines.get(i).text()).find());
            if (!continuation) {
                starts.add(i);
            }
        }
        int[] bounds = new int[starts.size() + 1];
        for (int k = 0; k < starts.size(); k++) {
            bounds[k] = starts.get(k);
        }
        bounds[starts.size()] = lines.size();
        return new Entries(bounds, lines.size());
    }

    int count() {
        return bounds.length - 1;
    }

    int start(int entry) {
        return bounds[entry];
    }

    int end(int entry) {
        return bounds[entry + 1];
    }

    /// Comparison key of an entry: the keys of its lines joined by newlines.
    String key(List<ProcessedLine> lines, int entry) {
        if (end(entry) - start(entry) == 1) {
            return lines.get(start(entry)).key();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start(entry); i < end(entry); i++) {
            if (i > start(entry)) {
                sb.append('\n');
            }
            sb.append(lines.get(i).key());
        }
        return sb.toString();
    }

    /// Diffs two logs entry by entry and returns line-level edits.
    ///
    /// @param a line symbols of the left log
    /// @param b line symbols of the right log
    static List<Edit> diff(List<ProcessedLine> left, Entries le, int[] a, List<ProcessedLine> right, Entries re,
                           int[] b, DiffAlgorithm algorithm, CancellationToken token) {
        Map<String, Integer> ids = new HashMap<>();
        int[] ea = new int[le.count()];
        int[] eb = new int[re.count()];
        for (int i = 0; i < ea.length; i++) {
            ea[i] = ids.computeIfAbsent(le.key(left, i), _ -> ids.size());
        }
        for (int i = 0; i < eb.length; i++) {
            eb[i] = ids.computeIfAbsent(re.key(right, i), _ -> ids.size());
        }
        List<Edit> out = new ArrayList<>();
        int pendingA = -1;
        int pendingB = -1;
        int lineA = 0;
        int lineB = 0;
        for (Edit e : algorithm.diff(ea, eb, token)) {
            if (e.type() == Edit.Type.EQUAL) {
                if (pendingA >= 0) {
                    subDiff(a, pendingA, lineA, b, pendingB, lineB, algorithm, token, out);
                    pendingA = -1;
                }
                int la = le.start(e.aStart());
                int lb = re.start(e.bStart());
                int len = le.start(e.aEnd()) - la; // bounds end with the line count, so this works at the end too
                out.add(new Edit(Edit.Type.EQUAL, la, la + len, lb, lb + len));
                lineA = la + len;
                lineB = lb + len;
            } else {
                if (pendingA < 0) {
                    pendingA = lineA;
                    pendingB = lineB;
                }
                lineA = le.start(e.aEnd());
                lineB = re.start(e.bEnd());
            }
        }
        if (pendingA >= 0) {
            subDiff(a, pendingA, lineA, b, pendingB, lineB, algorithm, token, out);
        }
        return out;
    }

    private static void subDiff(int[] a, int aFrom, int aTo, int[] b, int bFrom, int bTo, DiffAlgorithm algorithm,
                                CancellationToken token, List<Edit> out) {
        int[] sa = Arrays.copyOfRange(a, aFrom, aTo);
        int[] sb = Arrays.copyOfRange(b, bFrom, bTo);
        for (Edit e : algorithm.diff(sa, sb, token)) {
            out.add(new Edit(e.type(), e.aStart() + aFrom, e.aEnd() + aFrom, e.bStart() + bFrom, e.bEnd() + bFrom));
        }
    }
}
