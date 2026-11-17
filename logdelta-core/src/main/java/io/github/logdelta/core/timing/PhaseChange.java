// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.timing;

/// A stretch of the run, between two lines present in both logs, whose duration changed notably.
///
/// @param startRow    row of the line where the phase starts (present on both sides)
/// @param endRow      row of the line where the phase ends (present on both sides)
/// @param leftMillis  duration of the phase in the baseline
/// @param rightMillis duration of the phase in the candidate
/// @param startText   text of the start line (candidate side)
/// @param endText     text of the end line (candidate side)
public record PhaseChange(int startRow, int endRow, long leftMillis, long rightMillis, String startText,
                          String endText) {

    /// `rightMillis - leftMillis`: positive when the candidate is slower.
    public long delta() {
        return rightMillis - leftMillis;
    }

    /// How many times longer the candidate took (`Infinity` if the baseline took no time).
    public double factor() {
        return leftMillis == 0 ? Double.POSITIVE_INFINITY : rightMillis / (double) leftMillis;
    }

    /// Whether diff row `row` lies inside this phase.
    public boolean contains(int row) {
        return row >= startRow && row <= endRow;
    }
}
