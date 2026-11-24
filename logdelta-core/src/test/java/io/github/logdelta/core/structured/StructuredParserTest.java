// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.structured;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredParserTest {

    @Test
    @DisplayName("JSON lines become dotted fields")
    void json() {
        List<Field> f = StructuredParser.json("{\"level\":\"info\",\"http\":{\"status\":200}}");
        assertNotNull(f);
        assertEquals(2, f.size());
        assertEquals("http.status", f.get(1).name());
        assertEquals("status", f.get(1).simpleName());
        assertEquals("200", f.get(1).value());
        assertNull(StructuredParser.json("INFO plain text"));
        assertNull(StructuredParser.json("{broken"));
    }

    @Test
    @DisplayName("logfmt lines need at least two pairs and only keys or pairs")
    void logfmt() {
        List<Field> f = StructuredParser.logfmt("level=info msg=\"started \\\"web\\\" server\" port=8080 debug");
        assertNotNull(f);
        assertEquals(List.of("level", "msg", "port", "debug"), f.stream().map(Field::name).toList());
        assertEquals("started \"web\" server", f.get(1).value());
        assertEquals("true", f.get(3).value());
        assertNull(StructuredParser.logfmt("INFO order placed id=5"), "only one pair");
        assertNull(StructuredParser.logfmt("2026-01-01 level=info msg=x"), "a timestamp is not a key");
        assertNull(StructuredParser.logfmt("a=\"unterminated b=c"));
        String line = "a=1 b=\"x y\"";
        Field b = StructuredParser.logfmt(line).get(1);
        assertEquals("\"x y\"", line.substring(b.valueStart(), b.valueEnd()));
    }

    @Test
    @DisplayName("the format is detected from most lines")
    void detect() {
        assertEquals(LogFormat.JSON, StructuredParser.detect(List.of(List.of("{\"a\":1}", "{\"a\":2}", "banner"),
                List.of("{\"a\":3}"))));
        assertEquals(LogFormat.LOGFMT, StructuredParser.detect(List.of(List.of("a=1 b=2", "a=3 b=4", "x"))));
        assertEquals(LogFormat.PLAIN, StructuredParser.detect(List.of(List.of("INFO a", "INFO b=1"))));
        assertEquals(LogFormat.PLAIN, StructuredParser.detect(List.of(List.of(), List.of(""))));
    }

    @Test
    @DisplayName("log formats parse from their names")
    void formats() {
        assertEquals(LogFormat.JSON, LogFormat.parse("NDJSON"));
        assertEquals(LogFormat.PLAIN, LogFormat.parse("text"));
        assertTrue(LogFormat.LOGFMT.isStructured());
        assertFalse(LogFormat.AUTO.isStructured());
        assertThrows(IllegalArgumentException.class, () -> LogFormat.parse("xml"));
    }

    @Test
    @DisplayName("ignored fields match full names, last segments and globs, ignoring case")
    void fieldFilter() {
        FieldFilter f = FieldFilter.of(List.of("traceId", "trace.id", "*_ms", "http.request.*"));
        assertTrue(f.ignores("traceId"));
        assertTrue(f.ignores("span.TRACEID"));
        assertTrue(f.ignores("trace.id"));
        assertFalse(f.ignores("other.trace.id.x"));
        assertTrue(f.ignores("took_ms"));
        assertTrue(f.ignores("http.request.id"));
        assertFalse(f.ignores("http.response.status"));
        assertFalse(FieldFilter.of(List.of()).ignores("ts"));
    }
}
