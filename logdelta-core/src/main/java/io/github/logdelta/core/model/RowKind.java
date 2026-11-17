// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.model;

/// Classification of a row in a side-by-side comparison.
public enum RowKind {

    /// Both sides are equal after normalization.
    UNCHANGED("Unchanged", ' '),

    /// Both sides exist and are similar, but differ.
    MODIFIED("Modified", '~'),

    /// The line exists only on the left (baseline).
    REMOVED("Removed", '-'),

    /// The line exists only on the right (candidate).
    ADDED("Added", '+');

    private final String displayName;
    private final char marker;

    RowKind(String displayName, char marker) {
        this.displayName = displayName;
        this.marker = marker;
    }

    /// Human-readable name.
    public String displayName() {
        return displayName;
    }

    /// Single-character marker used in text output.
    public char marker() {
        return marker;
    }

    /// Whether the row represents a difference.
    public boolean isChange() {
        return this != UNCHANGED;
    }
}
