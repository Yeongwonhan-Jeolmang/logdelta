// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.io;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogReaderTest {

    @Test
    @DisplayName("splits on LF, CRLF and CR")
    void lineTerminators() throws IOException {
        LogFile f = read("a\nb\r\nc\rd".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("a", "b", "c", "d"), f.lines());
    }

    @Test
    @DisplayName("detects UTF-8 and strips the byte order mark")
    void utf8Bom() throws IOException {
        byte[] body = "héllo\n".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        LogFile f = read(withBom);
        assertEquals(StandardCharsets.UTF_8, f.charset());
        assertEquals(List.of("héllo"), f.lines());
    }

    @Test
    @DisplayName("falls back to ISO-8859-1 for invalid UTF-8")
    void latin1Fallback() throws IOException {
        LogFile f = read("café".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(StandardCharsets.ISO_8859_1, f.charset());
        assertEquals("café", f.lines().get(0));
    }

    @Test
    @DisplayName("decodes UTF-16 with a byte order mark")
    void utf16() throws IOException {
        byte[] body = "x\ny".getBytes(StandardCharsets.UTF_16LE);
        byte[] data = new byte[body.length + 2];
        data[0] = (byte) 0xFF;
        data[1] = (byte) 0xFE;
        System.arraycopy(body, 0, data, 2, body.length);
        assertEquals(List.of("x", "y"), read(data).lines());
    }

    @Test
    @DisplayName("decompresses gzip transparently")
    void gzip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write("one\ntwo\n".getBytes(StandardCharsets.UTF_8));
        }
        Path file = Files.createTempFile("logdelta", ".log.gz");
        try {
            Files.write(file, bytes.toByteArray());
            LogFile f = LogReader.read(file);
            assertTrue(f.compressed());
            assertEquals(List.of("one", "two"), f.lines());
            assertEquals(file.getFileName().toString(), f.displayName());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("UTF-8 sniffing tolerates a multi-byte sequence cut at the sample boundary")
    void truncatedSample() {
        byte[] euro = "€".getBytes(StandardCharsets.UTF_8); // 3 bytes
        byte[] sample = {'a', euro[0], euro[1]};
        assertTrue(LogReader.looksLikeUtf8(sample, true));
        assertFalse(LogReader.looksLikeUtf8(sample, false));
    }

    @Test
    @DisplayName("in-memory logs can be created from text")
    void ofText() {
        assertEquals(List.of("a", "b"), LogFile.ofText("x", "a\r\nb").lines());
        assertEquals(0, LogFile.ofText("x", "").lineCount());
    }

    private static LogFile read(byte[] data) throws IOException {
        return LogReader.read("test", new ByteArrayInputStream(data), null);
    }
}
