// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.model;

/// A contiguous block of rows that contains at least one change plus surrounding context.
///
/// @param startRow first row index (inclusive)
/// @param endRow   last row index (exclusive)
public record Hunk(int startRow, int endRow) {

    /// Validates the range.
    public Hunk {
        if (startRow < 0 || endRow <= startRow) {
            throw new IllegalArgumentException("Invalid hunk " + startRow + ".." + endRow);
        }
    }

    /// Number of rows in the hunk.
    public int size() {
        return endRow - startRow;
    }
}
