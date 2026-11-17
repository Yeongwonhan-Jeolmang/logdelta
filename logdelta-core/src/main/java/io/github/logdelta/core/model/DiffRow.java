// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.model;

import io.github.logdelta.core.diff.InlineHighlights;

/// One row of a side-by-side comparison.
///
/// @param kind       classification
/// @param left       index into the result's left lines, or `-1` if the row has no left side
/// @param right      index into the result's right lines, or `-1` if the row has no right side
/// @param inline     word-level highlights for [RowKind#MODIFIED] rows, otherwise
///                   [InlineHighlights#NONE]
/// @param movedPeer  for a removed or added line that reappears elsewhere, the index of the
///                   matching row; otherwise `-1`
/// @param expected   whether this difference is also seen in another baseline run, i.e. it is
///                   normal variation rather than a real change (only with several baselines)
public record DiffRow(RowKind kind, int left, int right, InlineHighlights inline, int movedPeer, boolean expected) {

    /// Normalizes a missing highlight object to [InlineHighlights#NONE].
    public DiffRow {
        if (inline == null) {
            inline = InlineHighlights.NONE;
        }
    }

    /// A row that is not marked as expected variation.
    public DiffRow(RowKind kind, int left, int right, InlineHighlights inline, int movedPeer) {
        this(kind, left, right, inline, movedPeer, false);
    }

    /// Whether the row is a difference that is not explained by another baseline run.
    public boolean isUnexpectedChange() {
        return kind.isChange() && !expected;
    }

    /// Whether the row is a difference.
    public boolean isChange() {
        return kind.isChange();
    }

    /// Whether this removed/added line was moved rather than truly removed or added.
    public boolean isMoved() {
        return movedPeer >= 0;
    }

    /// Whether the row has a left side.
    public boolean hasLeft() {
        return left >= 0;
    }

    /// Whether the row has a right side.
    public boolean hasRight() {
        return right >= 0;
    }
}
