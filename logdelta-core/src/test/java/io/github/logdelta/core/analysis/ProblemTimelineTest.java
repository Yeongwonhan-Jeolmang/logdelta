// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.analysis;

import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.engine.LogDeltaEngine;
import io.github.logdelta.core.io.LogFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProblemTimelineTest {

    @Test
    @DisplayName("problems are binned by time on a shared scale")
    void byTime() {
        LogFile a = LogFile.ofLines("a", List.of("2026-01-01 10:00:00 INFO start", "2026-01-01 10:00:10 WARN slow",
                "2026-01-01 10:00:20 INFO stop"));
        LogFile b = LogFile.ofLines("b", List.of("2026-01-02 09:00:00 INFO start", "2026-01-02 09:00:39 ERROR boom",
                "2026-01-02 09:00:40 INFO stop"));
        DiffResult r = new LogDeltaEngine().compare(a, b, DiffOptions.defaults());
        ProblemTimeline t = r.problemTimeline();
        assertTrue(t.byTime());
        assertEquals(40_000, t.spanMillis());
        assertEquals(ProblemTimeline.BINS, t.bins());
        assertEquals(1, t.left().totalWarnings());
        assertEquals(1, t.right().totalErrors());
        int errorBin = -1;
        for (int i = 0; i < t.bins(); i++) {
            if (t.right().errors()[i] > 0) {
                errorBin = i;
            }
        }
        assertTrue(errorBin > t.bins() * 9 / 10, "the error is near the end of the run");
        assertTrue(t.right().firstRows()[errorBin] >= 0);
        assertEquals(1, t.peak());
        assertTrue(t.hasProblems());
    }

    @Test
    @DisplayName("without timestamps, bins are positions in the file")
    void byPosition() {
        DiffResult r = new LogDeltaEngine().compare(LogFile.ofLines("a", List.of("ok", "ok")),
                LogFile.ofLines("b", List.of("ok", "ERROR x")), DiffOptions.defaults());
        assertFalse(r.problemTimeline().byTime());
        assertEquals(1, r.problemTimeline().right().errors()[ProblemTimeline.BINS / 2]);
    }
}
