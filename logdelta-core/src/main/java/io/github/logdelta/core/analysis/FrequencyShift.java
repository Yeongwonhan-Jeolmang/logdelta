// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.analysis;

/// A normalized message whose number of occurrences changed significantly between the logs.
/// Typical causes are retry loops, repeated timeouts or a cache that stopped working.
///
/// @param template   normalized text of the message
/// @param leftCount  occurrences in the left log
/// @param rightCount occurrences in the right log
/// @param firstRow   first row that contains the message on either side
public record FrequencyShift(String template, int leftCount, int rightCount, int firstRow) {

    /// `rightCount - leftCount`.
    public int delta() {
        return rightCount - leftCount;
    }
}
