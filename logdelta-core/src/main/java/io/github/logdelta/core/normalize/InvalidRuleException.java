// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.normalize;

/// Thrown when a rule definition is incomplete or its pattern does not compile.
public final class InvalidRuleException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /// @param message description of the problem, suitable for showing to users
    public InvalidRuleException(String message) {
        super(message);
    }

    /// @param message description of the problem; `cause` is usually a PatternSyntaxException
    public InvalidRuleException(String message, Throwable cause) {
        super(message, cause);
    }
}
