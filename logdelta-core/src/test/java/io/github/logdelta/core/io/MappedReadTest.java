// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.io;

import io.github.logdelta.core.timing.TimeWindow;
import io.github.logdelta.core.util.CancellationToken;
import io.github.logdelta.core.util.ProgressListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MappedReadTest {

    private static LogFile read(Path f, long threshold, long chunk, TimeWindow w) throws Exception {
        long oldThreshold = LogReader.mmapThreshold;
        long oldChunk = LogReader.mapChunk;
        LogReader.mmapThreshold = threshold;
        LogReader.mapChunk = chunk;
        try {
            return LogReader.read(f, null, ProgressListener.NONE, CancellationToken.NEVER, w);
        } finally {
            LogReader.mmapThreshold = oldThreshold;
            LogReader.mapChunk = oldChunk;
        }
    }

    @Test
    @DisplayName("memory-mapped reading gives the same lines as streaming, across chunk boundaries")
    void sameAsStreaming() throws Exception {
        String text = "﻿first\r\nsecond\rthird\n\nkorean 한국어 ✓\r\nlast without newline";
        Path f = Files.createTempFile("mapped", ".log");
        Files.writeString(f, text, StandardCharsets.UTF_8);
        LogFile streamed = read(f, Long.MAX_VALUE, 1 << 20, null);
        assertEquals(List.of("first", "second", "third", "", "korean 한국어 ✓", "last without newline"), streamed.lines());
        for (long chunk = 1; chunk <= 13; chunk++) {
            LogFile mapped = read(f, 0, chunk, null);
            assertEquals(streamed.lines(), mapped.lines(), "chunk size " + chunk);
            assertEquals(StandardCharsets.UTF_8, mapped.charset());
        }
    }

    @Test
    @DisplayName("Latin-1 files and time windows work on the mapped path")
    void latin1AndWindow() throws Exception {
        Path f = Files.createTempFile("mapped-latin1", ".log");
        Files.write(f, ("2026-01-01 10:00:00 café\n2026-01-01 10:05:00 later\n2026-01-01 10:10:00 end\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        LogFile all = read(f, 0, 8, null);
        assertEquals(StandardCharsets.ISO_8859_1, all.charset());
        assertEquals("2026-01-01 10:00:00 café", all.lines().getFirst());
        LogFile window = read(f, 0, 8, TimeWindow.parse("+5m", "+5m"));
        assertEquals(List.of("2026-01-01 10:05:00 later"), window.lines());
        assertEquals(2, window.firstLineNumber());
    }
}
