// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.diff;

import java.util.List;

/// Character ranges that differ between the two sides of a modified line.
///
/// @param left  changed ranges in the left line, ascending and non-overlapping
/// @param right changed ranges in the right line, ascending and non-overlapping
public record InlineHighlights(List<Span> left, List<Span> right) {

    /// Highlights with no ranges on either side.
    public static final InlineHighlights NONE = new InlineHighlights(List.of(), List.of());

    /// Defensively copies the lists.
    public InlineHighlights {
        left = List.copyOf(left);
        right = List.copyOf(right);
    }

    /// Whether neither side has a highlighted range.
    public boolean isEmpty() {
        return left.isEmpty() && right.isEmpty();
    }
}
