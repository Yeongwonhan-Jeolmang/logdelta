// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.timing;

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

class TimingTest {

    private static long t(String line) {
        return TimestampParser.parse(line);
    }

    @Test
    @DisplayName("parses common timestamp formats")
    void formats() {
        long base = t("2026-11-12 08:49:03.000 INFO x");
        assertEquals(base + 117, t("2026-11-12T08:49:03.117Z INFO x"));
        assertEquals(base + 120, t("2026-11-12 08:49:03,12 INFO x"));
        assertEquals(base, t("2026/11/12 08:49:03 [error] nginx"));
        assertEquals(base + 1000, t("{\"level\":\"info\",\"time\":\"2026-11-12T08:49:04Z\"}"));
        assertEquals(base, t("10.0.0.1 - - [12/Nov/2026:08:49:03 -0500] \"GET / HTTP/1.1\" 200"));
        assertEquals(t("Nov 12 08:49:03 host sshd[1]: x") + 2000, t("Nov 12 08:49:05 host sshd[1]: x"));
        assertEquals((8 * 3600 + 49 * 60 + 3) * 1000L + 117, t("08:49:03.117 [main] INFO x"));
        assertEquals(1762955343117L, t("1762955343.117 event"));
        assertEquals(1762955343117L, t("1762955343117 event"));
    }

    @Test
    @DisplayName("lines without timestamps and continuation lines are recognised")
    void none() {
        assertEquals(TimestampParser.NONE, t("\tat com.acme.Foo.bar(Foo.java:12)"));
        assertEquals(TimestampParser.NONE, t("java.lang.IllegalStateException: boom"));
        assertEquals(TimestampParser.NONE, t(""));
        assertEquals(TimestampParser.NONE, t("version 2.8.3 ready"));
        assertEquals(TimestampParser.NONE, t("2026-13-45 99:99:99 invalid"));
    }

    @Test
    @DisplayName("the timeline carries times forward and handles midnight")
    void timeline() {
        long n = TimestampParser.NONE;
        Timeline tl = Timeline.of(new long[] {n, 1000, n, 4000});
        assertEquals(Timeline.UNKNOWN, tl.offset(0));
        assertEquals(0, tl.offset(1));
        assertEquals(0, tl.offset(2));
        assertFalse(tl.hasOwnTimestamp(2));
        assertEquals(3000, tl.offset(3));
        assertEquals(3000, tl.duration());

        long day = 24L * 3600 * 1000;
        Timeline wrap = Timeline.of(new long[] {day - 1000, 500});
        assertEquals(1500, wrap.offset(1));
        assertEquals("+5.203s", Timeline.formatOffset(5203));
        assertEquals("850 ms", Timeline.format(850));
        assertEquals("1 min 23 s", Timeline.format(83_400));
    }

    @Test
    @DisplayName("a phase that takes much longer in the candidate is reported")
    void slowPhase() {
        List<String> left = List.of(
                "2026-01-01 10:00:00.000 INFO start",
                "2026-01-01 10:00:00.400 INFO loading cache",
                "2026-01-01 10:00:00.800 INFO cache ready",
                "2026-01-01 10:00:01.000 INFO done");
        List<String> right = List.of(
                "2026-02-02 11:00:00.000 INFO start",
                "2026-02-02 11:00:00.400 INFO loading cache",
                "2026-02-02 11:00:03.500 INFO cache ready",
                "2026-02-02 11:00:03.700 INFO done");
        DiffResult r = new LogDeltaEngine().compare(LogFile.ofLines("l", left), LogFile.ofLines("r", right),
                DiffOptions.defaults());
        TimingAnalysis t = r.timing();
        assertTrue(t.available());
        assertEquals(1000, t.left().duration());
        assertEquals(3700, t.right().duration());
        assertEquals(1, t.slower().size());
        PhaseChange p = t.slower().getFirst();
        assertEquals(400, p.leftMillis());
        assertEquals(3100, p.rightMillis());
        assertTrue(p.startText().endsWith("loading cache"));
        assertTrue(p.endText().endsWith("cache ready"));
        assertEquals(p, t.slowPhaseAt(2));
        assertTrue(t.faster().isEmpty());
    }

    @Test
    @DisplayName("logs without timestamps have no timing")
    void unavailable() {
        DiffResult r = new LogDeltaEngine().compare(LogFile.ofLines("l", List.of("a", "b")),
                LogFile.ofLines("r", List.of("a", "c")), DiffOptions.defaults());
        assertFalse(r.timing().available());
        assertTrue(r.timing().slower().isEmpty());
    }
}
