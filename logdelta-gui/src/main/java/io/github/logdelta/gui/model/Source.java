// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.model;

import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.io.LogSources;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/// Where a log comes from: a local file, an `http(s)` URL, or text held in memory (pasted
/// from the clipboard).
///
/// Sources are immutable values; the GUI keeps them instead of [Path]s so that every place
/// that accepts a file also accepts a URL or pasted text.
public sealed interface Source {

    /// Short name for tabs, headers and menus.
    String displayName();

    /// Full description for tooltips: the path, the URL or `"pasted text"`.
    String description();

    /// The source as it is written into a session file, or `null` if it cannot be saved there
    /// (pasted text).
    String spec();

    /// The local path, or `null` if the source is not a file.
    default Path path() {
        return null;
    }

    /// A local file.
    ///
    /// @param file absolute or relative path
    record File(Path file) implements Source {

        /// Validates the path.
        public File {
            Objects.requireNonNull(file, "file");
        }

        @Override
        public String displayName() {
            Path name = file.getFileName();
            return name == null ? file.toString() : name.toString();
        }

        @Override
        public String description() {
            return file.toString();
        }

        @Override
        public String spec() {
            return file.toString();
        }

        @Override
        public Path path() {
            return file;
        }
    }

    /// A log downloaded over HTTP(S).
    ///
    /// @param uri the address
    record Url(URI uri) implements Source {

        /// Validates the address.
        public Url {
            Objects.requireNonNull(uri, "uri");
        }

        @Override
        public String displayName() {
            return LogSources.displayName(uri);
        }

        @Override
        public String description() {
            return uri.toString();
        }

        @Override
        public String spec() {
            return uri.toString();
        }
    }

    /// Text held in memory, e.g. pasted from the clipboard.
    ///
    /// @param log the content
    record Text(LogFile log) implements Source {

        /// Validates the content.
        public Text {
            Objects.requireNonNull(log, "log");
        }

        @Override
        public String displayName() {
            return log.displayName();
        }

        @Override
        public String description() {
            return "pasted text, " + log.lineCount() + " lines";
        }

        @Override
        public String spec() {
            return null;
        }
    }

    /// A file source.
    static Source of(Path file) {
        return new File(file);
    }

    /// Parses what a user typed or what a session stored: a URL or a path.
    ///
    /// @throws IllegalArgumentException if a URL is malformed
    static Source parse(String spec) {
        String s = spec.strip();
        if (LogSources.isUrl(s)) {
            try {
                return new Url(LogSources.toUri(s));
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }
        return new File(Path.of(s));
    }
}
