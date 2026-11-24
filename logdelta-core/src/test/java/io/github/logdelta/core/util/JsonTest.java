// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    @DisplayName("parses every JSON value type")
    void parse() {
        Object v = Json.parse("{\"a\": [1, 2.5, -3e2, true, false, null], \"b\": {\"c\": \"x\\ny\\u00e9\"}}");
        Map<?, ?> m = assertInstanceOf(Map.class, v);
        List<?> a = assertInstanceOf(List.class, m.get("a"));
        assertEquals(1L, a.get(0));
        assertEquals(2.5, a.get(1));
        assertEquals(-300.0, a.get(2));
        assertEquals(Boolean.TRUE, a.get(3));
        assertNull(a.get(5));
        assertEquals("x\nyé", ((Map<?, ?>) m.get("b")).get("c"));
        assertEquals("x", Json.string(Json.parse("{\"k\":\"x\"}"), "k"));
        assertNull(Json.string(Json.parse("{\"k\":1}"), "k"));
    }

    @Test
    @DisplayName("rejects malformed documents")
    void errors() {
        for (String bad : List.of("", "{", "{\"a\":}", "[1,]", "{\"a\":1}x", "\"unterminated", "{a:1}", "01x",
                "\"\\q\"")) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(bad), bad);
        }
    }

    @Test
    @DisplayName("flattens objects and reports value offsets")
    void flatten() {
        String line = "{\"ts\":\"2026-01-01\",\"http\":{\"status\":502,\"path\":\"/a\"},\"tags\":[1,2],\"empty\":{}}";
        List<String> seen = new ArrayList<>();
        assertTrue(Json.flattenObject(line, (path, value, keyStart, valueStart, valueEnd) ->
                seen.add(path + "=" + value + "@" + line.substring(valueStart, valueEnd) + "@" + line.charAt(keyStart))));
        assertEquals(List.of("ts=2026-01-01@\"2026-01-01\"@\"", "http.status=502@502@\"",
                "http.path=/a@\"/a\"@\"", "tags=[1,2]@[1,2]@\"", "empty={}@{}@\""), seen);
        assertFalse(Json.flattenObject("[1,2]", (_, _, _, _, _) -> { }));
        assertFalse(Json.flattenObject("prefix {\"a\":1}", (_, _, _, _, _) -> { }));
        assertFalse(Json.flattenObject("{\"a\":1} trailing", (_, _, _, _, _) -> { }));
    }
}
