// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.diff;

import io.github.logdelta.core.normalize.Mask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineDiffTest {

    @Test
    @DisplayName("highlights only the changed word")
    void singleWord() {
        String l = "Order 42 placed";
        String r = "Order 42 rejected";
        InlineHighlights h = InlineDiff.compute(l, r);
        assertEquals(List.of(new Span(9, 15)), h.left());
        assertEquals(List.of(new Span(9, 17)), h.right());
        assertEquals("placed", l.substring(9, 15));
    }

    @Test
    @DisplayName("adjacent changed words merge into one phrase")
    void phrase() {
        InlineHighlights h = InlineDiff.compute("status ok fast", "status very bad slow");
        assertEquals(1, h.right().size(), h.toString());
        assertEquals(new Span(7, 20), h.right().get(0));
    }

    @Test
    @DisplayName("masked regions with the same replacement are not highlighted")
    void masksCompareByReplacement() {
        String l = "12:00:01 done";
        String r = "13:45:59 done";
        List<Mask> lm = List.of(new Mask(0, 8, "<TIME>", "time"));
        List<Mask> rm = List.of(new Mask(0, 8, "<TIME>", "time"));
        assertTrue(InlineDiff.compute(l, lm, r, rm, false, false).isEmpty());
        assertFalse(InlineDiff.compute(l, r).isEmpty(), "without masks the digits differ");
    }

    @Test
    @DisplayName("case and whitespace options are respected")
    void options() {
        assertTrue(InlineDiff.compute("Hello  World", List.of(), "hello World", List.of(), true, true).isEmpty());
        assertEquals(1, InlineDiff.compute("Hello", List.of(), "hello", List.of(), false, false).left().size());
    }

    @Test
    @DisplayName("tokenizer keeps offsets aligned with the original text")
    void tokenizer() {
        String s = "a.b  c";
        List<InlineDiff.Token> t = InlineDiff.tokenize(s, List.of(), false, false);
        assertEquals(5, t.size());
        StringBuilder rebuilt = new StringBuilder();
        t.forEach(tok -> rebuilt.append(s, tok.start(), tok.end()));
        assertEquals(s, rebuilt.toString());
    }
}
