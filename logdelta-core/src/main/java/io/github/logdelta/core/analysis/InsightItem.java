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
public record InsightItem(int row, int lineNumber, Severity severity, String text, int lines) {
}
