// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.analysis;

import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.engine.LogDeltaEngine;
import io.github.logdelta.core.io.LogFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightAnalyzerTest {

    private static DiffResult compare(List<String> l, List<String> r) {
        return new LogDeltaEngine().compare(LogFile.ofLines("l", l), LogFile.ofLines("r", r), DiffOptions.defaults());
    }

    @Test
    @DisplayName("frequency shifts surface retry loops")
    void retryLoop() {
        List<String> left = new ArrayList<>(List.of("start", "retrying request", "done"));
        List<String> right = new ArrayList<>(List.of("start"));
        for (int i = 0; i < 6; i++) {
            right.add("retrying request");
        }
        right.add("done");
        Insights in = compare(left, right).insights();
        assertEquals(1, in.frequencyShifts().size());
        FrequencyShift f = in.frequencyShifts().get(0);
        assertEquals("retrying request", f.template());
        assertEquals(1, f.leftCount());
        assertEquals(6, f.rightCount());
        assertEquals(5, f.delta());
        assertTrue(f.firstRow() >= 0);
    }

    @Test
    @DisplayName("errors that disappear are reported as resolved")
    void resolved() {
        Insights in = compare(List.of("INFO a", "ERROR b failed", "INFO c"), List.of("INFO a", "INFO c")).insights();
        assertEquals(1, in.resolvedProblems().size());
        assertEquals(2, in.resolvedProblems().get(0).lineNumber());
        assertTrue(in.newProblems().isEmpty());
    }

    @Test
    @DisplayName("an INFO line that becomes an ERROR is a new problem")
    void escalation() {
        Insights in = compare(List.of("INFO payment accepted for order"),
                List.of("ERROR payment rejected for order")).insights();
        assertEquals(1, in.newProblems().size());
        assertEquals(Severity.ERROR, in.newProblems().get(0).severity());
        assertEquals(1, in.leftCount(Severity.INFO));
        assertEquals(1, in.rightCount(Severity.ERROR));
    }
}
