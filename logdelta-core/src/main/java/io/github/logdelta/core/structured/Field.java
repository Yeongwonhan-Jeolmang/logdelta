// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.structured;

/// One field of a structured log line.
///
/// Nested JSON objects are flattened: `{"http":{"status":200}}` has a field named
/// `http.status`. Arrays are kept whole, as their JSON text.
///
/// @param name       field name; dotted path for nested objects
/// @param value      the value: unescaped for strings, the literal text otherwise
/// @param keyStart   offset in the line where the field's key starts
/// @param valueStart offset where the value starts (including an opening quote)
/// @param valueEnd   exclusive offset where the value ends (including a closing quote)
public record Field(String name, String value, int keyStart, int valueStart, int valueEnd) {

    /// Validates the offsets.
    public Field {
        if (keyStart < 0 || valueStart < keyStart || valueEnd < valueStart) {
            throw new IllegalArgumentException("Invalid field offsets " + keyStart + "/" + valueStart + "/" + valueEnd);
        }
    }

    /// The last segment of a dotted name (`status` for `http.status`).
    public String simpleName() {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }
}
