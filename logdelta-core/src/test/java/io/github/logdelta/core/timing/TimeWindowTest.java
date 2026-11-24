// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.timing;

import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.engine.LogDeltaEngine;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.io.LogReader;
import io.github.logdelta.core.util.CancellationToken;
import io.github.logdelta.core.util.ProgressListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeWindowTest {

    private static final List<String> LOG = List.of(
            "banner without time",
            "2026-11-12 08:00:00 INFO start",
            "2026-11-12 08:01:00 INFO one minute",
            "    at continuation",
            "2026-11-12 08:02:00 INFO two minutes",
            "2026-11-12 08:03:00 INFO three minutes",
            "2026-11-12 08:04:00 INFO four minutes");

    @Test
    @DisplayName("bounds parse as relative, absolute or time of day")
    void parse() {
        assertEquals(90_000, assertInstanceOf(TimeWindow.Relative.class, TimeWindow.parseBound("+1m30s")).millis());
        assertEquals(250, assertInstanceOf(TimeWindow.Relative.class, TimeWindow.parseBound("+250ms")).millis());
        assertEquals(5_400_000, assertInstanceOf(TimeWindow.Relative.class, TimeWindow.parseBound("+1.5h")).millis());
        assertEquals((8 * 3600 + 49 * 60 + 3) * 1000L + 500,
                assertInstanceOf(TimeWindow.TimeOfDay.class, TimeWindow.parseBound("08:49:03.5")).millisOfDay());
        TimeWindow.Bound abs = TimeWindow.parseBound("2026-11-12T08:49");
        assertEquals(TimestampParser.parse("2026-11-12 08:49:00 x"), assertInstanceOf(TimeWindow.Absolute.class, abs).millis());
        assertEquals("+1m30s", TimeWindow.parseBound("+90s").text());
        assertNull(TimeWindow.parse("", " "));
        assertThrows(IllegalArgumentException.class, () -> TimeWindow.parseBound("soon"));
        assertThrows(IllegalArgumentException.class, () -> TimeWindow.parseBound("25:00"));
    }

    @Test
    @DisplayName("a window keeps a contiguous stretch, with continuation lines, and remembers where it starts")
    void slice() {
        LogFile log = LogFile.ofLines("x", LOG).window(TimeWindow.parse("+1m", "+2m"));
        assertEquals(List.of("2026-11-12 08:01:00 INFO one minute", "    at continuation",
                "2026-11-12 08:02:00 INFO two minutes"), log.lines());
        assertEquals(3, log.firstLineNumber());
        assertEquals(4, log.lineNumber(1));
        LogFile byClock = LogFile.ofLines("x", LOG).window(TimeWindow.parse("08:03", null));
        assertEquals(2, byClock.lineCount());
        LogFile untilOnly = LogFile.ofLines("x", LOG).window(TimeWindow.parse(null, "+0s"));
        assertEquals(List.of("banner without time", "2026-11-12 08:00:00 INFO start"), untilOnly.lines());
    }

    @Test
    @DisplayName("reading a file with a window stops early; the engine numbers lines from the source")
    void readAndCompare() throws Exception {
        Path f = Files.createTempFile("window", ".log");
        Files.write(f, LOG);
        TimeWindow w = TimeWindow.parse("+2m", "+3m");
        LogFile read = LogReader.read(f, null, ProgressListener.NONE, CancellationToken.NEVER, w);
        assertEquals(2, read.lineCount());
        assertEquals(5, read.firstLineNumber());
        DiffResult r = new LogDeltaEngine().compare(List.of(f), f, DiffOptions.builder().timeWindow(w).build(),
                ProgressListener.NONE, CancellationToken.NEVER);
        assertEquals(5, r.leftLines().getFirst().number());
        LogFile mem = LogFile.ofLines("m", LOG);
        DiffResult inMemory = new LogDeltaEngine().compare(mem, mem, DiffOptions.builder().timeWindow(w).build());
        assertEquals(2, inMemory.stats().leftLines());
    }

    @Test
    @DisplayName("a time-of-day window can cross midnight, also in logs without dates")
    void midnight() {
        List<String> night = List.of(
                "2026-11-12 23:40:00 INFO a", "2026-11-12 23:55:00 INFO b", "2026-11-13 00:05:00 INFO c",
                "2026-11-13 00:20:00 INFO d");
        assertEquals(List.of("2026-11-12 23:55:00 INFO b", "2026-11-13 00:05:00 INFO c"),
                LogFile.ofLines("x", night).window(TimeWindow.parse("23:50", "00:10")).lines());
        List<String> clockOnly = night.stream().map(l -> l.substring(11)).toList();
        assertEquals(List.of("23:55:00 INFO b", "00:05:00 INFO c"),
                LogFile.ofLines("x", clockOnly).window(TimeWindow.parse("23:50", "00:10")).lines());
        assertEquals(List.of("00:05:00 INFO c"),
                LogFile.ofLines("x", clockOnly).window(TimeWindow.parse("00:00", "00:10")).lines(),
                "a start long before the first line means the next day");
        assertEquals(2, LogFile.ofLines("x", clockOnly).window(TimeWindow.parse(null, "23:59")).lineCount());
        assertEquals(LOG.size() - 1, LogFile.ofLines("x", LOG).window(TimeWindow.parse("07:59", null)).lineCount(),
                "a start just before the first line keeps the whole log");
    }

    @Test
    @DisplayName("a window that ends before it starts is an error; time zones are accepted")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> TimeWindow.parse("+5m", "+1m"));
        assertThrows(IllegalArgumentException.class, () -> TimeWindow.parse("2026-11-12T09:00", "2026-11-12T08:00"));
        assertEquals(TimeWindow.parseBound("2026-11-12T08:49:00"), TimeWindow.parseBound("2026-11-12T08:49:00Z"));
        assertEquals(TimeWindow.parseBound("2026-11-12T08:49"), TimeWindow.parseBound("2026-11-12T08:49+02:00"));
        assertNotNull(TimeWindow.parse("23:00", "01:00"), "times of day may wrap around");
    }
}
