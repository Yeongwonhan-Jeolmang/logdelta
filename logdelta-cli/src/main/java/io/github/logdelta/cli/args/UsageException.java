// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.args;

/// Thrown for invalid command-line usage; the message is shown to the user as-is.
public final class UsageException extends Exception {

    private static final long serialVersionUID = 1L;

    /// @param message what the user did wrong and, ideally, how to fix it
    public UsageException(String message) {
        super(message);
    }
}
