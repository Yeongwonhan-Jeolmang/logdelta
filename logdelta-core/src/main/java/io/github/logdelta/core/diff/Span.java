// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.diff;

/// A half-open character range `[start, end)` within a line.
///
/// @param start inclusive start offset
/// @param end   exclusive end offset
public record Span(int start, int end) {

    /// Validates the range.
    public Span {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Invalid span " + start + ".." + end);
        }
    }

    /// Number of characters covered.
    public int length() {
        return end - start;
    }

    /// Whether the span intersects `[from, to)`.
    public boolean overlaps(int from, int to) {
        return start < to && from < end;
    }
}
