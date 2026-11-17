// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.report;

/// Settings shared by all report writers.
///
/// @param contextLines unchanged lines shown around each change
/// @param fullContext  include every unchanged line (collapsed by default in HTML)
/// @param maxHunks     upper bound on the number of hunks written, or `0` for no limit;
///                     useful to keep Markdown summaries short
public record ReportOptions(int contextLines, boolean fullContext, int maxHunks) {

    /// Three lines of context, no limits.
    public static final ReportOptions DEFAULT = new ReportOptions(3, false, 0);

    /// Validates the values.
    public ReportOptions {
        if (contextLines < 0 || maxHunks < 0) {
            throw new IllegalArgumentException("contextLines and maxHunks must be >= 0");
        }
    }

    /// Returns a copy with a different number of context lines.
    public ReportOptions withContext(int lines) {
        return new ReportOptions(lines, fullContext, maxHunks);
    }

    /// Returns a copy with a hunk limit.
    public ReportOptions withMaxHunks(int max) {
        return new ReportOptions(contextLines, fullContext, max);
    }
}
