// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.analysis;

/// A message template whose number of occurrences changed notably, e.g. `Retry <*> for order <*>`
/// going from 0 to 340. Unlike a [FrequencyShift], which counts one exact (normalized) message,
/// a template covers every message that differs only in its variable parts.
///
/// @param template   the template text; `<*>` marks a variable part
/// @param leftCount  occurrences in the baseline
/// @param rightCount occurrences in the candidate
/// @param variants   number of distinct normalized messages the template covers
/// @param firstRow   first row that contains a message of this template on either side, or `-1`
/// @param severity   severity of the messages
public record TemplateShift(String template, int leftCount, int rightCount, int variants, int firstRow,
                            Severity severity) {

    /// `rightCount - leftCount`.
    public int delta() {
        return rightCount - leftCount;
    }
}
