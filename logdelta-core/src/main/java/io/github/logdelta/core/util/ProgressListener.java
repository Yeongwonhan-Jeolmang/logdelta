// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.util;

/// Receives coarse progress updates from long-running operations.
///
/// Implementations must be cheap and thread-safe: the engine calls them from the worker
/// thread that performs the comparison.
@FunctionalInterface
public interface ProgressListener {

    /// A listener that ignores every update.
    ProgressListener NONE = (_, _) -> { };

    /// Reports progress.
    ///
    /// @param stage    short human-readable description of the current stage, e.g. `"Diffing"`
    /// @param fraction overall completion in `[0, 1]`, or a negative value if unknown
    void onProgress(String stage, double fraction);
}
