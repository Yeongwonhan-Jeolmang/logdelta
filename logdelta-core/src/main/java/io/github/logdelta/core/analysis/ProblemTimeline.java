// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.analysis;

import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.ProcessedLine;
import io.github.logdelta.core.timing.Timeline;

import java.util.Arrays;
import java.util.List;

/// Warnings and errors over time, for both logs on the same scale: a small histogram that shows
/// at a glance when a run started to go wrong.
///
/// When both logs have timestamps, the x axis is the time since each run started and both use
/// the longer run's duration, so the same bin means the same moment in both runs. Otherwise the
/// axis is the position in the file (0 to 100%).
///
/// @param byTime     whether bins are time slices (`true`) or file-position slices
/// @param spanMillis length of the time axis in milliseconds (0 when `byTime` is false)
/// @param left       the baseline's counts
/// @param right      the candidate's counts
public record ProblemTimeline(boolean byTime, long spanMillis, Side left, Side right) {

    /// Number of bins used by [#of].
    public static final int BINS = 60;

    /// Counts for one log.
    ///
    /// @param warnings  warnings per bin
    /// @param errors    errors (and fatal messages) per bin
    /// @param firstRows diff row of the first problem in each bin, or `-1`
    public record Side(int[] warnings, int[] errors, int[] firstRows) {

        /// Copies the arrays.
        public Side {
            warnings = warnings.clone();
            errors = errors.clone();
            firstRows = firstRows.clone();
        }

        /// Total warnings.
        public int totalWarnings() {
            return Arrays.stream(warnings).sum();
        }

        /// Total errors.
        public int totalErrors() {
            return Arrays.stream(errors).sum();
        }

        /// The largest number of problems in any bin.
        public int peak() {
            int max = 0;
            for (int i = 0; i < warnings.length; i++) {
                max = Math.max(max, warnings[i] + errors[i]);
            }
            return max;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Side s && Arrays.equals(warnings, s.warnings) && Arrays.equals(errors, s.errors)
                    && Arrays.equals(firstRows, s.firstRows);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * Arrays.hashCode(warnings) + Arrays.hashCode(errors)) + Arrays.hashCode(firstRows);
        }

        @Override
        public String toString() {
            return "Side[warnings=" + Arrays.toString(warnings) + ", errors=" + Arrays.toString(errors) + "]";
        }
    }

    /// Number of bins.
    public int bins() {
        return left.warnings().length;
    }

    /// Whether either log has any warning or error.
    public boolean hasProblems() {
        return left.peak() > 0 || right.peak() > 0;
    }

    /// The largest bin of both logs, for a shared vertical scale.
    public int peak() {
        return Math.max(left.peak(), right.peak());
    }

    /// Builds the histogram.
    ///
    /// @param leftTimeline  timeline of the baseline (from [io.github.logdelta.core.timing.TimingAnalysis])
    /// @param rightTimeline timeline of the candidate
    public static ProblemTimeline of(List<ProcessedLine> left, List<ProcessedLine> right, Timeline leftTimeline,
                                     Timeline rightTimeline, List<DiffRow> rows) {
        int[] rowOfLeft = new int[left.size()];
        int[] rowOfRight = new int[right.size()];
        for (int i = 0; i < rows.size(); i++) {
            DiffRow r = rows.get(i);
            if (r.hasLeft()) {
                rowOfLeft[r.left()] = i;
            }
            if (r.hasRight()) {
                rowOfRight[r.right()] = i;
            }
        }
        boolean byTime = leftTimeline.isUsable() && rightTimeline.isUsable();
        long span = byTime ? Math.max(1, Math.max(leftTimeline.duration(), rightTimeline.duration())) : 0;
        return new ProblemTimeline(byTime, byTime ? span : 0,
                side(left, leftTimeline, rowOfLeft, byTime, span),
                side(right, rightTimeline, rowOfRight, byTime, span));
    }

    private static Side side(List<ProcessedLine> lines, Timeline timeline, int[] rowOf, boolean byTime, long span) {
        int[] warnings = new int[BINS];
        int[] errors = new int[BINS];
        int[] first = new int[BINS];
        Arrays.fill(first, -1);
        for (int i = 0; i < lines.size(); i++) {
            Severity s = SeverityDetector.detect(lines.get(i));
            if (!s.isProblem()) {
                continue;
            }
            int bin;
            if (byTime) {
                long t = timeline.offset(i);
                if (t == Timeline.UNKNOWN || t < 0) {
                    t = 0; // before the first timestamp, or a clock that went backwards
                }
                bin = (int) Math.min(BINS - 1, t * BINS / (span + 1));
            } else {
                bin = (int) Math.min(BINS - 1, (long) i * BINS / Math.max(1, lines.size()));
            }
            if (s == Severity.WARN) {
                warnings[bin]++;
            } else {
                errors[bin]++;
            }
            if (first[bin] < 0) {
                first[bin] = rowOf[i];
            }
        }
        return new Side(warnings, errors, first);
    }
}
