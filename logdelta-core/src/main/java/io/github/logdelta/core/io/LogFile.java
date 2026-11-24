// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.io;

import io.github.logdelta.core.timing.TimeWindow;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// A log that has been read into memory.
///
/// @param displayName name shown to users (usually the file name)
/// @param path        source path, or `null` when the log did not come from a file
/// @param lines       the lines without terminators; never `null`
/// @param charset     charset used to decode the content
/// @param compressed  whether the source was gzip-compressed
/// @param sizeBytes   size of the source in bytes (compressed size for gzip files)
/// @param firstLineNumber 1-based number, in the source, of the first line in `lines`; greater
///                    than 1 when a [TimeWindow] skipped the lines before it
/// @param window      the time window `lines` were cut to, or `null` for the whole log
public record LogFile(String displayName, Path path, List<String> lines, Charset charset,
                      boolean compressed, long sizeBytes, int firstLineNumber, TimeWindow window) {

    /// Validates and defensively copies the line list.
    public LogFile {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(charset, "charset");
        if (firstLineNumber < 1) {
            throw new IllegalArgumentException("firstLineNumber must be >= 1");
        }
        lines = List.copyOf(lines);
    }

    /// A log holding every line of its source.
    public LogFile(String displayName, Path path, List<String> lines, Charset charset, boolean compressed,
                   long sizeBytes) {
        this(displayName, path, lines, charset, compressed, sizeBytes, 1, null);
    }

    /// Creates an in-memory log, mostly useful for tests and pasted text.
    public static LogFile ofLines(String displayName, List<String> lines) {
        long size = lines.stream().mapToLong(l -> l.length() + 1L).sum();
        return new LogFile(displayName, null, lines, java.nio.charset.StandardCharsets.UTF_8, false, size);
    }

    /// Creates an in-memory log by splitting `text` on line terminators.
    public static LogFile ofText(String displayName, String text) {
        return ofLines(displayName, text.isEmpty() ? List.of() : List.of(text.split("\\r?\\n|\\r", -1)));
    }

    /// Number of lines.
    public int lineCount() {
        return lines.size();
    }

    /// Source line number (1-based) of the line at `index`.
    public int lineNumber(int index) {
        return firstLineNumber + index;
    }

    /// Returns the part of this log inside `w` (see [TimeWindow]); `this` if `w` is `null` or
    /// is the window already applied.
    public LogFile window(TimeWindow w) {
        if (w == null || w.equals(window)) {
            return this;
        }
        TimeWindow.Slicer slicer = w.slicer();
        List<String> kept = new ArrayList<>();
        int first = -1;
        for (int i = 0; i < lines.size(); i++) {
            TimeWindow.Slicer.Decision d = slicer.accept(lines.get(i));
            if (d == TimeWindow.Slicer.Decision.STOP) {
                break;
            }
            if (d == TimeWindow.Slicer.Decision.KEEP) {
                if (first < 0) {
                    first = i;
                }
                kept.add(lines.get(i));
            }
        }
        int firstNumber = first < 0 ? firstLineNumber + lines.size() : firstLineNumber + first;
        return new LogFile(displayName, path, kept, charset, compressed, sizeBytes, firstNumber, w);
    }
}
