// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.io;

import java.nio.charset.Charset;
import java.nio.file.Path;
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
public record LogFile(String displayName, Path path, List<String> lines, Charset charset,
                      boolean compressed, long sizeBytes) {

    /// Validates and defensively copies the line list.
    public LogFile {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(charset, "charset");
        lines = List.copyOf(lines);
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
}
