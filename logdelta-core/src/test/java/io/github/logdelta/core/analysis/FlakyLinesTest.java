// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.analysis;

import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.engine.LogDeltaEngine;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.normalize.Normalizer;
import io.github.logdelta.core.normalize.RuleSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlakyLinesTest {

    @Test
    @DisplayName("lines that vary between good runs are reported with a fix")
    void flaky() {
        LogFile good1 = LogFile.ofLines("good1", List.of("INFO start", "INFO cache warmed entries=1204",
                "INFO Started with PID 4242", "INFO stop"));
        LogFile good2 = LogFile.ofLines("good2", List.of("INFO start", "INFO cache warmed entries=1190",
                "INFO Started with PID 5151", "INFO optional plugin loaded from disk", "INFO stop"));
        LogFile cand = LogFile.ofLines("cand", List.of("INFO start", "INFO cache warmed entries=1200",
                "INFO Started with PID 7777", "INFO stop"));
        DiffResult r = new LogDeltaEngine().compare(good1, List.of(good2), cand, DiffOptions.defaults(),
                io.github.logdelta.core.util.ProgressListener.NONE, io.github.logdelta.core.util.CancellationToken.NEVER);
        List<FlakyLine> flaky = r.insights().flakyLines();
        FlakyLine entries = flaky.stream().filter(f -> f.text().contains("cache warmed")).findFirst().orElseThrow();
        assertEquals(FlakyLine.Kind.VALUES, entries.kind());
        assertNotNull(entries.rule());
        Normalizer n = new Normalizer(RuleSet.defaults().withAt(0, entries.rule()), false, false);
        assertEquals(n.key("INFO cache warmed entries=1"), n.key("INFO cache warmed entries=2"));
        assertEquals(2, entries.totalRuns());
        assertEquals(1, entries.varyingRuns());
        FlakyLine plugin = flaky.stream().filter(f -> f.text().contains("optional plugin")).findFirst().orElseThrow();
        assertEquals(FlakyLine.Kind.PRESENCE, plugin.kind());
        assertNotNull(plugin.ignorePattern());
        assertTrue("INFO optional plugin loaded from disk".matches(".*" + plugin.ignorePattern() + ".*"));
    }

    @Test
    @DisplayName("lines with the same fix are reported once, counting the others")
    void grouped() {
        LogFile a = LogFile.ofLines("a", List.of("Charged ORD-111111 amount=1.00", "Charged ORD-222222 amount=2.00",
                "Charged ORD-333333 amount=3.00"));
        LogFile b = LogFile.ofLines("b", List.of("Charged ORD-444444 amount=4.00", "Charged ORD-555555 amount=5.00",
                "Charged ORD-666666 amount=6.00"));
        DiffResult r = new LogDeltaEngine().compare(a, List.of(b), a, DiffOptions.defaults(),
                io.github.logdelta.core.util.ProgressListener.NONE, io.github.logdelta.core.util.CancellationToken.NEVER);
        List<FlakyLine> flaky = r.insights().flakyLines();
        assertEquals(1, flaky.size());
        assertEquals(2, flaky.getFirst().similarLines());
        assertTrue(flaky.getFirst().rules().size() >= 2, "both the order number and the amount are masked");
    }
}
