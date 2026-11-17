// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.model;

import java.util.Locale;
import java.util.Objects;

/// Restricts which rows the comparison view shows.
///
/// @param mode which kind of rows to keep
/// @param text only keep rows whose baseline or candidate text contains this (ignoring case);
///             empty keeps everything
public record ViewFilter(Mode mode, String text) {

    /// Shows every row.
    public static final ViewFilter NONE = new ViewFilter(Mode.ALL, "");

    /// Kinds of rows to keep.
    public enum Mode {
        /// Every row (unchanged regions may still be collapsed).
        ALL("All lines"),
        /// Changed rows only.
        CHANGES("Changes"),
        /// Rows that belong to a new warning or error, including its stack trace.
        PROBLEMS("New problems"),
        /// Rows where either side is an ERROR or FATAL line.
        ERRORS("Errors");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        /// Label for the filter bar.
        public String label() {
            return label;
        }
    }

    /// Validates the values.
    public ViewFilter {
        Objects.requireNonNull(mode, "mode");
        text = text == null ? "" : text;
    }

    /// Whether this filter hides anything.
    public boolean active() {
        return mode != Mode.ALL || !text.isBlank();
    }

    /// Returns a copy with another mode.
    public ViewFilter withMode(Mode m) {
        return new ViewFilter(m, text);
    }

    /// Returns a copy with another text.
    public ViewFilter withText(String t) {
        return new ViewFilter(mode, t);
    }

    /// The text in lower case, for matching.
    String needle() {
        return text.strip().toLowerCase(Locale.ROOT);
    }
}
