// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.diff;

import io.github.logdelta.core.util.CancellationToken;

import java.util.List;

/// Computes an edit script between two sequences of interned symbols.
///
/// Lines (or tokens) are mapped to integers beforehand so that algorithms compare ints instead
/// of strings; equal symbols mean equal elements.
public interface DiffAlgorithm {

    /// Computes the edits that turn `a` into `b`.
    ///
    /// The returned script covers both sequences completely and in order: concatenating the
    /// `a` ranges of all EQUAL and DELETE edits yields `0..a.length`, and likewise for
    /// `b` with EQUAL and INSERT edits.
    ///
    /// @param token checked periodically; cancellation throws
    ///              [io.github.logdelta.core.util.CancelledException]
    List<Edit> diff(int[] a, int[] b, CancellationToken token);

    /// Convenience overload without cancellation.
    default List<Edit> diff(int[] a, int[] b) {
        return diff(a, b, CancellationToken.NEVER);
    }

    /// Short human-readable name, e.g. `"Myers"`.
    String name();
}
