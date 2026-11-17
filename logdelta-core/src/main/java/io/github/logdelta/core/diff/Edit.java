// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.diff;

/// One operation of an edit script that turns sequence `a` into sequence `b`.
///
/// Ranges are half-open. For [Type#EQUAL] both ranges have the same length; for
/// [Type#DELETE] the `b` range is empty and marks the position in `b`; for
/// [Type#INSERT] the `a` range is empty.
///
/// @param type   kind of operation
/// @param aStart start index in `a`
/// @param aEnd   end index in `a` (exclusive)
/// @param bStart start index in `b`
/// @param bEnd   end index in `b` (exclusive)
public record Edit(Type type, int aStart, int aEnd, int bStart, int bEnd) {

    /// Edit operation kinds.
    public enum Type {
        EQUAL, DELETE, INSERT
    }

    /// Number of elements of `a` covered.
    public int aLength() {
        return aEnd - aStart;
    }

    /// Number of elements of `b` covered.
    public int bLength() {
        return bEnd - bStart;
    }
}
