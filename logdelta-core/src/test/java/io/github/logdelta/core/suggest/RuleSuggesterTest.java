// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.suggest;

import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.engine.LogDeltaEngine;
import io.github.logdelta.core.io.LogFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleSuggesterTest {

    private static DiffResult compare(List<String> l, List<String> r) {
        return new LogDeltaEngine().compare(LogFile.ofLines("l", l), LogFile.ofLines("r", r), DiffOptions.defaults());
    }

    @Test
    @DisplayName("suggests masking a key=value that differs on every line")
    void keyValue() {
        List<String> l = new ArrayList<>();
        List<String> r = new ArrayList<>();
        String[] users = {"bob", "alice", "carol", "dave", "erin", "frank"};
        for (int i = 0; i < 6; i++) {
            l.add("login ok user=" + users[i] + " role=r" + i + " session=sx" + (1000 + i) + "Ab");
            r.add("login ok user=" + users[i] + " role=r" + i + " session=qz" + (7000 + i) + "Cd");
        }
        List<RuleSuggestion> s = compare(l, r).suggestions();
        assertEquals(1, s.size());
        RuleSuggestion first = s.getFirst();
        assertEquals(6, first.resolves());
        assertTrue(first.summary().contains("session"), first.summary());
        assertEquals("$1<SESSION>", first.rule().replacement());
        assertEquals("login ok user=bob role=r1 session=<SESSION>",
                new io.github.logdelta.core.normalize.Normalizer(
                        io.github.logdelta.core.normalize.RuleSet.of(List.of(first.rule())), false, false)
                        .normalize("login ok user=bob role=r1 session=abc123").normalized());
    }

    @Test
    @DisplayName("does not suggest hiding values that repeat, such as status codes")
    void categorical() {
        List<String> l = new ArrayList<>();
        List<String> r = new ArrayList<>();
        String[] paths = {"/a", "/b", "/c", "/d", "/e", "/f"};
        for (int i = 0; i < 6; i++) {
            l.add("request " + paths[i] + " finished status=OK code 201");
            r.add("request " + paths[i] + " finished status=FAILED code 502");
        }
        assertTrue(compare(l, r).suggestions().isEmpty());
    }

    @Test
    @DisplayName("derives patterns from the shape of ids")
    void shape() {
        RuleSuggester.Candidate c = RuleSuggester.fromShape("ORD-123456", "ORD-654321");
        assertNotNull(c);
        assertEquals("\\bORD-\\d{6}\\b", c.regex);
        assertEquals("ORD-<N>", c.replacement);
        assertNull(RuleSuggester.fromShape("12", "34"), "short bare numbers are too generic");
        assertNotNull(RuleSuggester.fromShape("deadbeef01", "0123abcdef"));
        assertNull(RuleSuggester.fromShape("alpha", "beta"));
    }
}
