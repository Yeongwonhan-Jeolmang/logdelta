// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.normalize;

/// A region of an original line that a rule replaced.
///
/// @param start       inclusive start offset in the original line
/// @param end         exclusive end offset in the original line
/// @param replacement the text that stands in for the region in the normalized line
/// @param ruleId      id of the rule that produced the mask
public record Mask(int start, int end, String replacement, String ruleId) {

    /// Validates the offsets.
    public Mask {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Invalid mask range " + start + ".." + end);
        }
    }

    /// Length of the masked region in the original line.
    public int length() {
        return end - start;
    }
}
