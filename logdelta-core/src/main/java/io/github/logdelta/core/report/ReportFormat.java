// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.report;

import java.util.Locale;

/// File-based output formats supported by [Reports].
public enum ReportFormat {
    HTML("HTML report", "html"),
    JSON("JSON", "json"),
    MARKDOWN("Markdown summary", "md"),
    UNIFIED("Unified diff", "diff"),
    JUNIT("JUnit XML", "xml");

    private final String displayName;
    private final String extension;

    ReportFormat(String displayName, String extension) {
        this.displayName = displayName;
        this.extension = extension;
    }

    /// Human-readable name, e.g. for file chooser filters.
    public String displayName() {
        return displayName;
    }

    /// Conventional file extension without the dot.
    public String extension() {
        return extension;
    }

    /// Lower-case key used on the command line.
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /// Parses a key (`html`, `json`, `markdown`/`md`, `unified`/`diff`, `junit`/`xml`).
    public static ReportFormat parse(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "html" -> HTML;
            case "json" -> JSON;
            case "markdown", "md" -> MARKDOWN;
            case "unified", "diff", "patch" -> UNIFIED;
            case "junit", "junit-xml", "xml" -> JUNIT;
            default -> throw new IllegalArgumentException("Unknown report format: " + s);
        };
    }
}
