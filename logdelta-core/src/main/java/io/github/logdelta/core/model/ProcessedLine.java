// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.model;

import io.github.logdelta.core.normalize.Mask;
import io.github.logdelta.core.structured.Field;

import java.util.List;

/// A line that took part in a comparison.
///
/// @param number     1-based line number in the source file
/// @param text       original text
/// @param normalized text after rules were applied
/// @param masks      regions of `text` replaced by rules (and, for structured lines, the values
///                   of ignored fields)
/// @param key        comparison key (normalized text plus case/whitespace options; for structured
///                   lines, the sorted fields that are not ignored)
/// @param fields     the fields of a JSON or logfmt line, in line order; empty for plain text
public record ProcessedLine(int number, String text, String normalized, List<Mask> masks, String key,
                            List<StructuredValue> fields) {

    /// Defensively copies the masks and fields.
    public ProcessedLine {
        masks = List.copyOf(masks);
        fields = List.copyOf(fields);
    }

    /// A plain-text line.
    public ProcessedLine(int number, String text, String normalized, List<Mask> masks, String key) {
        this(number, text, normalized, masks, key, List.of());
    }

    /// Whether any rule changed this line.
    public boolean isMasked() {
        return !masks.isEmpty();
    }

    /// Whether the line was compared field by field.
    public boolean isStructured() {
        return !fields.isEmpty();
    }

    /// The first field with the given name, or `null`.
    public StructuredValue field(String name) {
        for (StructuredValue f : fields) {
            if (f.field().name().equals(name)) {
                return f;
            }
        }
        return null;
    }

    /// A field of a structured line after normalization.
    ///
    /// @param field      the parsed field, with offsets into [ProcessedLine#text()]
    /// @param normalized the value with rule masks applied
    /// @param ignored    whether the field is left out of the comparison
    public record StructuredValue(Field field, String normalized, boolean ignored) {

        /// The field name.
        public String name() {
            return field.name();
        }

        /// The raw value.
        public String value() {
            return field.value();
        }
    }
}
