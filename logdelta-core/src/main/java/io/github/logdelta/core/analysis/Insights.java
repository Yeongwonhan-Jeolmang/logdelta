// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.analysis;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/// Findings that help answer "what went wrong?" faster than reading the whole diff.
///
/// @param newProblems        warnings/errors present only on the right (grouped)
/// @param resolvedProblems   warnings/errors present only on the left (grouped)
/// @param leftSeverities     number of left lines per severity
/// @param rightSeverities    number of right lines per severity
/// @param firstDivergenceRow index of the first changed row, or `-1` if identical
/// @param frequencyShifts    messages whose occurrence count changed notably, largest first
public record Insights(List<InsightItem> newProblems, List<InsightItem> resolvedProblems,
                       Map<Severity, Integer> leftSeverities, Map<Severity, Integer> rightSeverities,
                       int firstDivergenceRow, List<FrequencyShift> frequencyShifts) {

    /// Empty insights for identical inputs.
    public static final Insights EMPTY = new Insights(List.of(), List.of(),
            new EnumMap<>(Severity.class), new EnumMap<>(Severity.class), -1, List.of());

    /// Defensively copies all collections.
    public Insights {
        newProblems = List.copyOf(newProblems);
        resolvedProblems = List.copyOf(resolvedProblems);
        leftSeverities = Collections.unmodifiableMap(new EnumMap<>(copyOrEmpty(leftSeverities)));
        rightSeverities = Collections.unmodifiableMap(new EnumMap<>(copyOrEmpty(rightSeverities)));
        frequencyShifts = List.copyOf(frequencyShifts);
    }

    private static Map<Severity, Integer> copyOrEmpty(Map<Severity, Integer> m) {
        return m.isEmpty() ? new EnumMap<>(Severity.class) : m;
    }

    /// Count of left lines with the given severity.
    public int leftCount(Severity s) {
        return leftSeverities.getOrDefault(s, 0);
    }

    /// Count of right lines with the given severity.
    public int rightCount(Severity s) {
        return rightSeverities.getOrDefault(s, 0);
    }
}
