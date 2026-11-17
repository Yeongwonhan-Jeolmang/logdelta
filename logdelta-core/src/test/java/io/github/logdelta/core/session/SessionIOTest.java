// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.session;

import io.github.logdelta.core.diff.Algorithm;
import io.github.logdelta.core.engine.DiffOptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionIOTest {

    @Test
    @DisplayName("a session round-trips sources, options, bookmarks and notes")
    void roundTrip() throws Exception {
        Path dir = Files.createTempDirectory("logdelta-session");
        Path good = dir.resolve("logs/good.log");
        Session s = new Session(good.toString(), "https://ci.example.com/job/7/app.log",
                List.of(dir.resolve("logs/good-2.log").toString()),
                DiffOptions.builder().algorithm(Algorithm.MYERS).ignoreCase(true).addIgnorePattern("^DEBUG").build(),
                List.of(new Bookmark(Bookmark.Side.RIGHT, 57, "ERROR \"quoted\" \\ path", "first sign\nof the outage"),
                        new Bookmark(Bookmark.Side.LEFT, 3, "INFO start", "")),
                "Started after the pool resize.");
        Path file = dir.resolve("review.ldsession");
        SessionIO.write(s, file);

        String text = Files.readString(file);
        assertTrue(text.contains("left = \"logs/good.log\""), text);

        Session back = SessionIO.read(file);
        assertEquals(s, back);
        assertEquals(Bookmark.Side.LEFT, back.bookmarks().getFirst().side());
    }

    @Test
    @DisplayName("errors carry the line number")
    void errors() {
        SessionParseException e = assertThrows(SessionParseException.class,
                () -> SessionIO.parse("[session]\nleft = a\nright = b\n\n[bookmark]\nside = up\nline = 3\n", null));
        assertEquals(6, e.line());

        SessionParseException p = assertThrows(SessionParseException.class,
                () -> SessionIO.parse("[session]\nleft = a\nright = b\n[options]\nalgorithm = bogus\n", null));
        assertEquals(5, p.line());

        assertThrows(SessionParseException.class, () -> SessionIO.parse("[options]\n", null));
    }

    @Test
    @DisplayName("bookmarks find their line again after the log shifts")
    void relocate() {
        Bookmark b = new Bookmark(Bookmark.Side.RIGHT, 3, "ERROR boom", "");
        assertEquals(3, b.locate(List.of("a", "b", "ERROR boom")));
        assertEquals(5, b.locate(List.of("x", "y", "a", "b", "ERROR boom")));
        assertEquals(1, b.locate(List.of("ERROR boom", "c")));
        assertEquals(2, b.locate(List.of("gone", "too")));
        assertEquals(0, b.locate(List.of()));
    }
}
