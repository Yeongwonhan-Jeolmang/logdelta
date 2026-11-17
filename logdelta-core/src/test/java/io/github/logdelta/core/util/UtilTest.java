// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UtilTest {

    @Test
    @DisplayName("JsonWriter produces compact and pretty output")
    void json() {
        StringBuilder sb = new StringBuilder();
        JsonWriter w = new JsonWriter(sb, false);
        w.beginObject().name("a").value(1).name("b").beginArray().value("x\"y\n").value(true).nullValue().endArray()
                .name("c").value(1.5).endObject();
        assertEquals("{\"a\":1,\"b\":[\"x\\\"y\\n\",true,null],\"c\":1.5}", sb.toString());

        StringBuilder pretty = new StringBuilder();
        new JsonWriter(pretty, true).beginObject().name("k").beginArray().value(1).endArray().endObject();
        assertEquals("{\n  \"k\": [\n    1\n  ]\n}", pretty.toString());
    }

    @Test
    @DisplayName("JsonWriter rejects malformed structures")
    void jsonErrors() {
        JsonWriter w = new JsonWriter(new StringBuilder(), false);
        w.beginObject();
        assertThrows(IllegalStateException.class, () -> w.value(1));
        assertThrows(IllegalStateException.class, w::endArray);
    }

    @Test
    @DisplayName("text helpers")
    void text() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;", Text.escapeHtml("<a href=\"x\">&"));
        assertEquals("ab…", Text.truncate("abcdef", 3));
        assertEquals("abc", Text.truncate("abc", 3));
        assertEquals("1.5 KB", Text.humanBytes(1536));
        assertEquals("512 B", Text.humanBytes(512));
        assertEquals("a   b", Text.expandTabs("a\tb", 4));
        assertEquals("\\*x\\*", Text.escapeMarkdown("*x*"));
    }
}
