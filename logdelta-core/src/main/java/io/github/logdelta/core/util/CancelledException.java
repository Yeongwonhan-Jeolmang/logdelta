// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.util;

/// Thrown when an operation stops because its [CancellationToken] was cancelled.
public final class CancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /// Creates the exception with a fixed message.
    public CancelledException() {
        super("Operation cancelled");
    }
}
