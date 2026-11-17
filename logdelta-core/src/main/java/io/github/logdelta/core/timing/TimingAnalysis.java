// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.timing;

import java.util.List;

/// Timing of both runs and the phases whose duration changed.
///
/// @param left     timeline of the baseline, indexed like `DiffResult.leftLines()`
/// @param right    timeline of the candidate, indexed like `DiffResult.rightLines()`
/// @param slower   phases that took notably longer in the candidate, largest increase first
/// @param faster   phases that got notably faster, largest decrease first
public record TimingAnalysis(Timeline left, Timeline right, List<PhaseChange> slower, List<PhaseChange> faster) {

    /// Copies the lists.
    public TimingAnalysis {
        slower = List.copyOf(slower);
        faster = List.copyOf(faster);
    }

    /// Whether both logs carry enough timestamps to compare timing.
    public boolean available() {
        return left.isUsable() && right.isUsable();
    }

    /// The slow phase containing diff row `row`, or `null`.
    public PhaseChange slowPhaseAt(int row) {
        for (PhaseChange p : slower) {
            if (p.contains(row)) {
                return p;
            }
        }
        return null;
    }
}
