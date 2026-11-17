// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.timing;

import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.ProcessedLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/// Finds phases of a run that got slower or faster.
///
/// Lines present in both logs (unchanged or modified rows whose lines carry their own timestamp)
/// serve as **anchors**. For every pair of consecutive anchors, the time between them is
/// compared in both runs. A gap counts as a change when it grew (or shrank) by at least
/// [#MIN_CHANGE_MILLIS] and by at least 2% of the longer run, and by a factor of at least
/// [#MIN_FACTOR]. Consecutive changed gaps are merged into one phase.
public final class TimingAnalyzer {

    /// Smallest absolute change reported.
    public static final long MIN_CHANGE_MILLIS = 250;
    /// Smallest relative change reported.
    public static final double MIN_FACTOR = 1.5;
    /// Maximum number of phases reported per direction.
    public static final int MAX_PHASES = 10;

    private TimingAnalyzer() {
    }

    /// Analyzes timing; `leftTimes`/`rightTimes` are raw [TimestampParser] values per line.
    public static TimingAnalysis analyze(List<ProcessedLine> left, List<ProcessedLine> right, long[] leftTimes,
                                         long[] rightTimes, List<DiffRow> rows) {
        Timeline lt = Timeline.of(leftTimes);
        Timeline rt = Timeline.of(rightTimes);
        if (!lt.isUsable() || !rt.isUsable()) {
            return new TimingAnalysis(lt, rt, List.of(), List.of());
        }
        long threshold = Math.max(MIN_CHANGE_MILLIS, Math.max(lt.duration(), rt.duration()) / 50);

        List<Integer> anchors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            DiffRow r = rows.get(i);
            if (r.hasLeft() && r.hasRight() && lt.hasOwnTimestamp(r.left()) && rt.hasOwnTimestamp(r.right())) {
                anchors.add(i);
            }
        }
        List<PhaseChange> slower = new ArrayList<>();
        List<PhaseChange> faster = new ArrayList<>();
        PhaseChange open = null;
        int openDirection = 0;
        for (int k = 1; k < anchors.size(); k++) {
            DiffRow a = rows.get(anchors.get(k - 1));
            DiffRow b = rows.get(anchors.get(k));
            long dl = lt.offset(b.left()) - lt.offset(a.left());
            long dr = rt.offset(b.right()) - rt.offset(a.right());
            int direction = classify(dl, dr, threshold);
            if (direction != 0 && direction == openDirection) {
                open = new PhaseChange(open.startRow(), anchors.get(k), open.leftMillis() + dl,
                        open.rightMillis() + dr, open.startText(), right.get(b.right()).text());
            } else {
                close(open, openDirection, slower, faster);
                open = direction == 0 ? null : new PhaseChange(anchors.get(k - 1), anchors.get(k), dl, dr,
                        right.get(a.right()).text(), right.get(b.right()).text());
                openDirection = direction;
            }
        }
        close(open, openDirection, slower, faster);
        slower.sort(Comparator.comparingLong(PhaseChange::delta).reversed());
        faster.sort(Comparator.comparingLong(PhaseChange::delta));
        return new TimingAnalysis(lt, rt, limit(slower), limit(faster));
    }

    private static int classify(long dl, long dr, long threshold) {
        if (dl < 0 || dr < 0) {
            return 0; // clocks went backwards (e.g. interleaved threads); not meaningful
        }
        if (dr - dl >= threshold && dr >= MIN_FACTOR * dl) {
            return 1;
        }
        if (dl - dr >= threshold && dl >= MIN_FACTOR * dr) {
            return -1;
        }
        return 0;
    }

    private static void close(PhaseChange p, int direction, List<PhaseChange> slower, List<PhaseChange> faster) {
        if (p != null) {
            (direction > 0 ? slower : faster).add(p);
        }
    }

    private static List<PhaseChange> limit(List<PhaseChange> list) {
        return list.size() > MAX_PHASES ? list.subList(0, MAX_PHASES) : list;
    }
}
