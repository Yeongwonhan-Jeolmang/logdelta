// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.structured;

import java.util.Locale;

/// How the lines of a log are interpreted.
///
/// Structured lines are compared **field by field**: the order of fields does not matter,
/// [ignored fields][io.github.logdelta.core.engine.DiffOptions#ignoreFields()] are left out, and a
/// modified line highlights the fields that changed instead of character runs.
public enum LogFormat {

    /// Decide from the content: JSON when most lines are JSON objects, logfmt when most lines
    /// are `key=value` pairs, plain text otherwise.
    AUTO("Automatic", "Detects JSON lines and logfmt; plain text otherwise"),

    /// Every line is plain text.
    PLAIN("Plain text", "Compare lines as text"),

    /// One JSON object per line (JSON Lines, NDJSON, most structured loggers).
    JSON("JSON lines", "One JSON object per line; compared field by field"),

    /// `key=value` pairs separated by spaces, as written by logfmt, Go's slog and Heroku.
    LOGFMT("logfmt", "key=value pairs; compared field by field");

    private final String displayName;
    private final String description;

    LogFormat(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
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

    /// Whether lines in this format are split into fields.
    public boolean isStructured() {
        return this == JSON || this == LOGFMT;
    }

    @Override
    public String toString() {
        return displayName;
    }

    /// Parses a key case-insensitively (`auto`, `plain`/`text`, `json`/`jsonl`/`ndjson`, `logfmt`).
    ///
    /// @throws IllegalArgumentException for unknown names
    public static LogFormat parse(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> AUTO;
            case "plain", "text", "none" -> PLAIN;
            case "json", "jsonl", "ndjson", "json-lines" -> JSON;
            case "logfmt", "kv", "key-value" -> LOGFMT;
            default -> throw new IllegalArgumentException(
                    "Unknown log format '" + s + "' (expected auto, plain, json or logfmt)");
        };
    }
}
