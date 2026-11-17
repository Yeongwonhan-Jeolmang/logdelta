// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.normalize;

import java.util.List;

/// The result of normalizing a single line.
///
/// @param original   the line as read
/// @param normalized the line with every mask replaced by its replacement text
/// @param masks      masked regions in ascending, non-overlapping order (offsets refer to
///                   `original`)
public record NormalizedText(String original, String normalized, List<Mask> masks) {

    /// Defensively copies the mask list.
    public NormalizedText {
        masks = List.copyOf(masks);
    }

    /// Returns the mask covering `offset` in the original line, or `null`.
    public Mask maskAt(int offset) {
        for (Mask m : masks) {
            if (offset >= m.start() && offset < m.end()) {
                return m;
            }
            if (m.start() > offset) {
                break;
            }
        }
        return null;
    }

    /// Whether any rule changed the line.
    public boolean isMasked() {
        return !masks.isEmpty();
    }
}
