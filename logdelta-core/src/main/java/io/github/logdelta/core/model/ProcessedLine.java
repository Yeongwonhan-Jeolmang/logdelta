// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.model;

import io.github.logdelta.core.normalize.Mask;

import java.util.List;

/// A line that took part in a comparison.
///
/// @param number     1-based line number in the source file
/// @param text       original text
/// @param normalized text after rules were applied
/// @param masks      regions of `text` replaced by rules
/// @param key        comparison key (normalized text plus case/whitespace options)
public record ProcessedLine(int number, String text, String normalized, List<Mask> masks, String key) {

    /// Defensively copies the masks.
    public ProcessedLine {
        masks = List.copyOf(masks);
    }

    /// Whether any rule changed this line.
    public boolean isMasked() {
        return !masks.isEmpty();
    }
}
