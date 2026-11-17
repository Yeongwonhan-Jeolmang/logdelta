// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.github.logdelta.gui.view.SyntaxHighlighter.LEVEL_ERROR;
import static io.github.logdelta.gui.view.SyntaxHighlighter.LEVEL_INFO;
import static io.github.logdelta.gui.view.SyntaxHighlighter.LEVEL_WARN;
import static io.github.logdelta.gui.view.SyntaxHighlighter.LOGGER;
import static io.github.logdelta.gui.view.SyntaxHighlighter.PLAIN;
import static io.github.logdelta.gui.view.SyntaxHighlighter.STACK;
import static io.github.logdelta.gui.view.SyntaxHighlighter.THREAD;
import static io.github.logdelta.gui.view.SyntaxHighlighter.TIMESTAMP;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SyntaxHighlighterTest {

    /// Renders the styles as one letter per character, for readable assertions.
    private static String sketch(String line) {
        byte[] s = SyntaxHighlighter.styles(line);
        StringBuilder sb = new StringBuilder();
        for (byte b : s) {
            sb.append(switch (b) {
                case TIMESTAMP -> 'T';
                case LEVEL_ERROR -> 'E';
                case LEVEL_WARN -> 'W';
                case LEVEL_INFO -> 'I';
                case LOGGER -> 'L';
                case THREAD -> 'H';
                case STACK -> 'S';
                case PLAIN -> '.';
                default -> '?';
            });
        }
        return sb.toString();
    }

    @Test
    @DisplayName("logback layout: timestamp, level, thread, logger; message stays plain")
    void logback() {
        String line = "2026-11-11 22:41:37.000 INFO  [main] c.a.checkout.App - Started in 1.2 s";
        assertEquals("TTTTTTTTTTTTTTTTTTTTTTT.IIII..HHHHHH.LLLLLLLLLLLLLLLL...................",
                sketch(line));
    }

    @Test
    @DisplayName("thread before level, Spring pid, python-style logger and stack frames")
    void variants() {
        assertEquals("TTTTTTTTTTTT.HHHHHH.WWWW.LLLLLLL....",
                sketch("10:00:00.123 [main] WARN c.e.Foo - x"));
        assertEquals("TTTTTTTTTTTTTTTTTTT.EEEEE.........HHHHHH.LLLLLLLLLL....",
                sketch("2026-01-01 10:00:00 ERROR 123 --- [main] o.s.b.Boot : x"));
        assertEquals("IIII.LLLL....", sketch("INFO root: hi"));
        assertEquals("SSSSSSSSSSSSSSSSS", sketch("\tat a.B.c(B.java)"));
        assertEquals("EEEEEEEEEEEEEEEEEEEEEEEEEEEE...........",
                sketch("java.io.UncheckedIOException: disk full"));
        assertEquals("........", sketch("no level"));
    }
}
