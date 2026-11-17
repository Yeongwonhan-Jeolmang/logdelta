// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.engine;

import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.RowKind;
import io.github.logdelta.core.util.CancellationToken;
import io.github.logdelta.core.util.ProgressListener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiBaselineTest {

    private static final LogDeltaEngine ENGINE = new LogDeltaEngine();

    @Test
    @DisplayName("differences that another good run explains are expected, not problems")
    void expected() {
        LogFile good1 = LogFile.ofLines("good1", List.of("start", "metrics exporter connected", "work", "stop"));
        LogFile good2 = LogFile.ofLines("good2", List.of("start", "work", "WARN cache refresh was slow", "stop"));
        LogFile bad = LogFile.ofLines("bad", List.of("start", "work", "WARN cache refresh was slow",
                "ERROR payment failed", "stop"));
        DiffResult r = ENGINE.compare(good1, List.of(good2), bad, DiffOptions.defaults(), ProgressListener.NONE,
                CancellationToken.NEVER);
        assertEquals(2, r.stats().expected());
        assertEquals(1, r.stats().unexpectedChanges());
        assertFalse(r.isIdentical());
        assertEquals(1, r.insights().newProblems().size());
        assertTrue(r.insights().newProblems().getFirst().text().contains("payment"));
        DiffRow removed = r.rows().stream().filter(x -> x.kind() == RowKind.REMOVED).findFirst().orElseThrow();
        assertTrue(removed.expected(), "missing from another good run, so it is flaky");
        assertEquals(1, r.extraBaselines().size());

        DiffResult single = ENGINE.compare(good1, bad, DiffOptions.defaults());
        assertEquals(1, single.insights().newProblems().size(), "WARN and ERROR are adjacent: one group");
        assertEquals(2, single.insights().newProblems().getFirst().lines());
        assertEquals(1, r.insights().newProblems().getFirst().lines(), "the WARN line is expected variation");
        assertEquals(0, single.stats().expected());
    }

    @Test
    @DisplayName("a candidate that only differs like the baselines do counts as identical")
    void identicalWithinVariation() {
        LogFile a = LogFile.ofLines("a", List.of("x", "flaky one", "y"));
        LogFile b = LogFile.ofLines("b", List.of("x", "y", "flaky two"));
        LogFile c = LogFile.ofLines("c", List.of("x", "y", "flaky two"));
        DiffResult r = ENGINE.compare(a, List.of(b), c, DiffOptions.defaults(), ProgressListener.NONE,
                CancellationToken.NEVER);
        assertTrue(r.isIdentical());
        assertTrue(r.stats().changes() > 0);
    }
}
