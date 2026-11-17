// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.analysis;

/// Log severity levels, ordered from least to most severe.
public enum Severity {
    NONE("-"),
    TRACE("TRACE"),
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR"),
    FATAL("FATAL");

    private final String label;

    Severity(String label) {
        this.label = label;
    }

    /// Short label for display.
    public String label() {
        return label;
    }

    /// Whether this level indicates a problem (warning or worse).
    public boolean isProblem() {
        return compareTo(WARN) >= 0;
    }
}
