// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli;

/// Process exit codes, following the `diff(1)` convention so that LogDelta can be dropped
/// into existing scripts and CI checks.
public final class ExitCode {

    /// No differences (or the command succeeded).
    public static final int OK = 0;

    /// Differences were found (or, with `--exit-code problems`, new problems were found).
    public static final int DIFFERENT = 1;

    /// Invalid usage, unreadable input or another error.
    public static final int ERROR = 2;

    private ExitCode() {
    }
}
