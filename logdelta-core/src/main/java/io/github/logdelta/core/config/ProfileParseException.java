// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.config;

/// Thrown when a profile file cannot be parsed; the message includes the line number.
public final class ProfileParseException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int line;

    /// @param line    1-based line number, or `0` if not applicable
    /// @param message description of the problem
    /// @param cause   underlying exception, may be `null`
    public ProfileParseException(int line, String message, Throwable cause) {
        super(line > 0 ? "line " + line + ": " + message : message, cause);
        this.line = line;
    }

    /// 1-based line number of the problem, or `0`.
    public int line() {
        return line;
    }
}
