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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateMinerTest {

    private static List<String> templates(String... messages) {
        TemplateMiner m = new TemplateMiner();
        for (String s : messages) {
            m.add("INFO", s);
        }
        m.finish();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < messages.length; i++) {
            out.add(m.text(m.templateOf(i)));
        }
        return out;
    }

    @Test
    @DisplayName("numbers, quoted values and key=value values become <*>")
    void shape() {
        assertEquals(List.of("Retry <*> for order <*>", "Retry <*> for order <*>"),
                templates("Retry 1 for order ORD-42", "Retry 2 for order ORD-43"));
        assertEquals(List.of("user=<*> done"), templates("user=42 done"));
        assertEquals(List.of("opened <*>"), templates("opened 'report.pdf'"));
        assertEquals(List.of("took <DURATION>"), templates("took <DURATION>"));
    }

    @Test
    @DisplayName("a word becomes a variable only when it takes at least three values")
    void cardinality() {
        List<String> t = templates("User alice logged in", "User bob logged in", "User carol logged in");
        assertEquals("User <*> logged in", t.getFirst());
        List<String> two = templates("Payment succeeded", "Payment failed");
        assertNotEquals(two.get(0), two.get(1), "success and failure must stay apart");
    }

    @Test
    @DisplayName("severity separates templates")
    void severity() {
        TemplateMiner m = new TemplateMiner();
        m.add("INFO", "Disk usage 10");
        m.add("ERROR", "Disk usage 99");
        m.finish();
        assertNotEquals(m.templateOf(0), m.templateOf(1));
        assertEquals(2, m.size());
    }

    @Test
    @DisplayName("insights report template shifts and collapse repeated new problems")
    void insights() {
        List<String> base = new ArrayList<>(List.of("INFO start", "INFO stop"));
        List<String> cand = new ArrayList<>(List.of("INFO start"));
        for (int i = 0; i < 6; i++) {
            cand.add("ERROR Retry " + i + " for order " + (100 + i) + " failed");
            cand.add("INFO working on item " + i);
        }
        cand.add("INFO stop");
        DiffResult r = new LogDeltaEngine().compare(LogFile.ofLines("a", base), LogFile.ofLines("b", cand),
                DiffOptions.builder().preset("none").build());
        Insights in = r.insights();
        assertEquals(1, in.newProblems().size(), "six separate retries are one problem");
        InsightItem item = in.newProblems().getFirst();
        assertEquals(6, item.occurrences());
        assertTrue(item.repeated());
        assertEquals("Retry <*> for order <*> failed", item.template());
        assertTrue(in.templateShifts().stream().anyMatch(t -> t.template().equals("Retry <*> for order <*> failed")
                && t.leftCount() == 0 && t.rightCount() == 6 && t.variants() == 6));
        assertEquals(6, in.newProblemOccurrences());
    }

    @Test
    @DisplayName("templates with fewer than two words are not reported")
    void specific() {
        assertFalse(InsightAnalyzer.isSpecific("at <*>"));
        assertFalse(InsightAnalyzer.isSpecific("<TIMESTAMP> <*>"));
        assertTrue(InsightAnalyzer.isSpecific("Retry <*> for order <*>"));
    }
}
