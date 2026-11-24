// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.analysis;

/// A notable change, for example an error that only appears in the candidate log.
///
/// Consecutive problem lines (such as a stack trace) are grouped into one item; [#lines()]
/// says how many rows the group spans.
///
/// @param row        index of the first row of the group
/// @param lineNumber 1-based line number of the first line in its file
/// @param severity   highest severity within the group
/// @param text       text of the first line
/// @param lines      number of rows in the group
/// @param occurrences how many times a problem of the same [message template][TemplateMiner]
///                   appears as new (or resolved); items of one template are reported once
/// @param template   the message template, e.g. `Retry <*> for order <*>`, or `null`
public record InsightItem(int row, int lineNumber, Severity severity, String text, int lines, int occurrences,
                          String template) {

    /// Validates the counts.
    public InsightItem {
        if (lines < 1 || occurrences < 1) {
            throw new IllegalArgumentException("lines and occurrences must be >= 1");
        }
    }

    /// A single occurrence without a template.
    public InsightItem(int row, int lineNumber, Severity severity, String text, int lines) {
        this(row, lineNumber, severity, text, lines, 1, null);
    }

    /// Whether the item stands for several occurrences of the same template.
    public boolean repeated() {
        return occurrences > 1;
    }
}
