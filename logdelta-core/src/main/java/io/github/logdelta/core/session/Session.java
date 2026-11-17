// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.session;

import io.github.logdelta.core.engine.DiffOptions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/// A saved comparison: which logs were compared, with which options, plus the reviewer's
/// bookmarks and notes. Stored as a `*.ldsession` file by [SessionIO].
///
/// Sources are kept as strings because they may be local paths or `http(s)` URLs (see
/// [io.github.logdelta.core.io.LogSources]).
///
/// @param left           baseline source
/// @param right          candidate source
/// @param extraBaselines further known-good runs; differences they explain count as normal
/// @param options        comparison options, including custom rules
/// @param bookmarks      bookmarks ordered by side, then line
/// @param notes          free-form notes about the comparison as a whole
public record Session(String left, String right, List<String> extraBaselines, DiffOptions options,
                      List<Bookmark> bookmarks, String notes) {

    /// Validates the values, copies the lists and sorts the bookmarks.
    public Session {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(options, "options");
        extraBaselines = List.copyOf(extraBaselines);
        List<Bookmark> sorted = new ArrayList<>(bookmarks);
        sorted.sort(Comparator.comparing(Bookmark::side).thenComparingInt(Bookmark::line));
        bookmarks = List.copyOf(sorted);
        notes = notes == null ? "" : notes;
    }

    /// A session for two sources with default options and no annotations.
    public static Session of(String left, String right) {
        return new Session(left, right, List.of(), DiffOptions.defaults(), List.of(), "");
    }

    /// Returns a copy with different bookmarks.
    public Session withBookmarks(List<Bookmark> newBookmarks) {
        return new Session(left, right, extraBaselines, options, newBookmarks, notes);
    }

    /// Returns a copy with different notes.
    public Session withNotes(String newNotes) {
        return new Session(left, right, extraBaselines, options, bookmarks, newNotes);
    }

    /// Returns a copy with different options.
    public Session withOptions(DiffOptions newOptions) {
        return new Session(left, right, extraBaselines, newOptions, bookmarks, notes);
    }
}
