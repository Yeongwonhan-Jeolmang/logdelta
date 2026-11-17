// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.model;

/// Counts that summarise a comparison.
///
/// @param leftLines    lines read from the left file
/// @param rightLines   lines read from the right file
/// @param leftIgnored  left lines excluded by ignore patterns
/// @param rightIgnored right lines excluded by ignore patterns
/// @param unchanged    unchanged rows
/// @param modified     modified rows
/// @param removed      removed rows (including moved-out lines)
/// @param added        added rows (including moved-in lines)
/// @param moved        number of lines detected as moved (pairs)
/// @param leftMasked   left lines changed by at least one rule
/// @param rightMasked  right lines changed by at least one rule
/// @param expected     changed rows explained by another baseline run (see [DiffRow#expected()])
public record DiffStats(int leftLines, int rightLines, int leftIgnored, int rightIgnored,
                        int unchanged, int modified, int removed, int added, int moved,
                        int leftMasked, int rightMasked, int expected) {

    /// Total number of changed rows, including expected ones.
    public int changes() {
        return modified + removed + added;
    }

    /// Changed rows that no baseline run explains.
    public int unexpectedChanges() {
        return changes() - expected;
    }

    /// Whether the logs are identical after normalization, apart from expected variation.
    public boolean identical() {
        return unexpectedChanges() == 0;
    }

    /// Fraction of compared lines that are unchanged, in `[0, 1]`. Two empty inputs are
    /// considered fully similar.
    public double similarity() {
        int compared = (leftLines - leftIgnored) + (rightLines - rightIgnored);
        return compared == 0 ? 1.0 : (2.0 * unchanged) / compared;
    }
}
