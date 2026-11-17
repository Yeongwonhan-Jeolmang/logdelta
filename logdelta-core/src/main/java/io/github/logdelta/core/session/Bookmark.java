// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.session;

import java.util.List;
import java.util.Objects;

/// A marked line with an optional note, saved as part of a [Session].
///
/// Bookmarks refer to a line by its number *and* keep a copy of the line's text. When a log
/// is regenerated and lines shift, [#locate(List)] finds the same text again nearby, so a
/// note such as "this is where the retry storm starts" survives a re-run.
///
/// @param side   which log the line belongs to
/// @param line   1-based line number in that log
/// @param text   the line's text when the bookmark was set, used for relocation
/// @param note   free-form note, may be empty and may span several lines
public record Bookmark(Side side, int line, String text, String note) {

    /// How far (in lines, each direction) [#locate(List)] searches for moved text.
    public static final int SEARCH_RADIUS = 2000;

    /// The log a bookmark belongs to.
    public enum Side {
        /// The baseline (left) log.
        LEFT,
        /// The candidate (right) log.
        RIGHT;

        /// Lower-case key used in session files.
        public String key() {
            return this == LEFT ? "left" : "right";
        }

        /// Parses `left`/`baseline` or `right`/`candidate`.
        public static Side parse(String s) {
            return switch (s.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "left", "baseline" -> LEFT;
                case "right", "candidate" -> RIGHT;
                default -> throw new IllegalArgumentException("Unknown side: " + s);
            };
        }
    }

    /// Validates the values.
    public Bookmark {
        Objects.requireNonNull(side, "side");
        if (line < 1) {
            throw new IllegalArgumentException("line must be >= 1");
        }
        text = text == null ? "" : text;
        note = note == null ? "" : note;
    }

    /// Returns a copy with a different note.
    public Bookmark withNote(String newNote) {
        return new Bookmark(side, line, text, newNote);
    }

    /// Returns a copy pointing at another line.
    public Bookmark withLine(int newLine) {
        return new Bookmark(side, newLine, text, note);
    }

    /// Finds the bookmarked line in (possibly changed) content.
    ///
    /// Returns [#line()] when the text there still matches (or no text was stored), otherwise
    /// the nearest line within [#SEARCH_RADIUS] whose text is identical. When the text is
    /// gone, the original line number is returned, clamped to the log length.
    ///
    /// @param lines the log's lines (index 0 is line 1)
    /// @return the 1-based line number, or `0` for an empty log
    public int locate(List<String> lines) {
        if (lines.isEmpty()) {
            return 0;
        }
        int at = Math.min(line, lines.size());
        if (text.isEmpty() || lines.get(at - 1).equals(text)) {
            return at;
        }
        for (int d = 1; d <= SEARCH_RADIUS; d++) {
            int below = line + d;
            int above = line - d;
            if (below <= lines.size() && lines.get(below - 1).equals(text)) {
                return below;
            }
            if (above >= 1 && above <= lines.size() && lines.get(above - 1).equals(text)) {
                return above;
            }
            if (below > lines.size() && above < 1) {
                break;
            }
        }
        return at;
    }
}
