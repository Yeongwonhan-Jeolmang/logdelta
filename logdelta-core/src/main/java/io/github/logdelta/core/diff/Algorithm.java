// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.diff;

import java.util.Locale;

/// The diff algorithms LogDelta offers.
public enum Algorithm {

    /// Myers' O(ND) algorithm with linear-space bisection. Produces a shortest edit script; with
    /// the default heuristic it stays fast on very different inputs at the cost of minimality.
    MYERS("Myers", "Shortest edit script; best general-purpose choice"),

    /// Patience diff: anchors on lines that occur exactly once in both inputs and recurses between
    /// them. Produces more readable results when blocks of similar lines move around.
    PATIENCE("Patience", "Anchors on unique lines; reads better when sections are reordered");

    private final String displayName;
    private final String description;

    Algorithm(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    /// Creates an implementation.
    public DiffAlgorithm create(boolean minimal) {
        return switch (this) {
            case MYERS -> new MyersDiff(minimal);
            case PATIENCE -> new PatienceDiff(minimal);
        };
    }

    /// Human-readable name.
    public String displayName() {
        return displayName;
    }

    /// One-line explanation for help texts and tooltips.
    public String description() {
        return description;
    }

    /// Lower-case key used on the command line and in profiles.
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /// Parses a key case-insensitively.
    ///
    /// @throws IllegalArgumentException for unknown names
    public static Algorithm parse(String s) {
        for (Algorithm a : values()) {
            if (a.name().equalsIgnoreCase(s.trim())) {
                return a;
            }
        }
        throw new IllegalArgumentException("Unknown algorithm '" + s + "' (expected myers or patience)");
    }

    @Override
    public String toString() {
        return displayName;
    }
}
