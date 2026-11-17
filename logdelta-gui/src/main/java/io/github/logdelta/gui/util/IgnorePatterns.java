// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.util;

import io.github.logdelta.core.model.ProcessedLine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Builds ignore patterns from example lines ("Ignore lines like this").
public final class IgnorePatterns {

    private static final Pattern PLACEHOLDER = Pattern.compile("<[A-Z]+>");

    private IgnorePatterns() {
    }

    /// Turns a normalized line into a regular expression that matches other occurrences of the
    /// same message: literal text is quoted and every placeholder (`<TIMESTAMP>`,
    /// `<UUID>`, ...) becomes a lazy wildcard.
    public static String suggest(ProcessedLine line) {
        String normalized = line.normalized().strip();
        StringBuilder sb = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(normalized);
        int pos = 0;
        while (m.find()) {
            sb.append(Pattern.quote(normalized.substring(pos, m.start()))).append(".*?");
            pos = m.end();
        }
        sb.append(Pattern.quote(normalized.substring(pos)));
        return sb.toString().replace("\\Q\\E", "");
    }
}
