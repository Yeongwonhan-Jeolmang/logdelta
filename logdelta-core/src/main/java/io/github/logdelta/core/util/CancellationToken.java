// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.util;

/// Cooperative cancellation flag shared between a caller and a long-running operation.
///
/// Operations poll [#throwIfCancelled()] at safe points. Once cancelled, a token stays
/// cancelled.
public final class CancellationToken {

    /// A token that can never be cancelled.
    public static final CancellationToken NEVER = new CancellationToken();

    private volatile boolean cancelled;

    /// Requests cancellation. Has no effect on [#NEVER].
    public void cancel() {
        if (this != NEVER) {
            cancelled = true;
        }
    }

    /// Returns whether cancellation has been requested.
    public boolean isCancelled() {
        return cancelled;
    }

    /// Throws if cancellation has been requested.
    ///
    /// @throws CancelledException if [#cancel()] was called
    public void throwIfCancelled() {
        if (cancelled) {
            throw new CancelledException();
        }
    }
}
