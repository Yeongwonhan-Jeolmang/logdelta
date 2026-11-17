// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.model;

/// One visual row of the comparison view: a [Line] showing a diff row, a [Fold] standing in
/// for a run of collapsed unchanged rows, or a [Gap] marking rows hidden by a [ViewFilter].
///
/// The hierarchy is sealed, so a `switch` over a `ViewRow` is checked for
/// exhaustiveness by the compiler:
///
/// ```java
/// switch (viewRow) {
///     case ViewRow.Line(int row) -> paintLine(row);
///     case ViewRow.Fold fold     -> paintFold(fold.hiddenCount());
///     case ViewRow.Gap gap       -> paintGap(gap.hiddenCount());
/// }
/// ```
public sealed interface ViewRow {

    /// A visible diff row.
    ///
    /// @param row index into `DiffResult.rows()`
    record Line(int row) implements ViewRow {
    }

    /// A collapsed run of unchanged diff rows `[start, end)`.
    ///
    /// @param start first hidden diff row (inclusive)
    /// @param end   last hidden diff row (exclusive)
    record Fold(int start, int end) implements ViewRow {

        /// Validates the range.
        public Fold {
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("Invalid fold " + start + ".." + end);
            }
        }

        /// Number of diff rows hidden by the fold.
        public int hiddenCount() {
            return end - start;
        }

        /// Whether diff row `row` is hidden by this fold.
        public boolean contains(int row) {
            return row >= start && row < end;
        }
    }

    /// Rows `[start, end)` hidden because they do not pass the active [ViewFilter]. Unlike a
    /// [Fold], a gap cannot be expanded; clearing the filter shows the rows again.
    ///
    /// @param start first hidden diff row (inclusive)
    /// @param end   last hidden diff row (exclusive)
    record Gap(int start, int end) implements ViewRow {

        /// Validates the range.
        public Gap {
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("Invalid gap " + start + ".." + end);
            }
        }

        /// Number of diff rows hidden by the gap.
        public int hiddenCount() {
            return end - start;
        }
    }

    /// The first diff row this visual row represents.
    default int firstRow() {
        return switch (this) {
            case Line(int row) -> row;
            case Fold(int start, int _) -> start;
            case Gap(int start, int _) -> start;
        };
    }
}
