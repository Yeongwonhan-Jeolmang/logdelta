// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.util;

import io.github.logdelta.core.structured.Field;
import io.github.logdelta.core.structured.StructuredLines;
import io.github.logdelta.core.structured.StructuredParser;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Display helpers for log lines in narrow places (insight lists, terminal summaries), where the common
/// prefix (timestamp, level, thread, logger) would push the actual message out of view. The full
/// line is always available as a tooltip.
public final class LogText {

    private static final Pattern LEADING = Pattern.compile(
            "^\\s*(?:(?:<[A-Z]+>|\\d{4}-\\d{2}-\\d{2}[T ][\\d:.,]+\\S*)\\s*)+");
    private static final Pattern LEVEL = Pattern.compile(
            "^(TRACE|DEBUG|INFO|NOTICE|WARN(?:ING)?|ERROR|SEVERE|FATAL|CRITICAL)\\b\\s*");
    private static final int MAX_PREFIX = 140;

    private LogText() {
    }

    /// Returns the message part of a typical log line: leading timestamps are dropped and, when a
    /// `" - "` separator follows the logger name, everything before it as well.
    ///
    /// ```
    /// "2026-11-12 08:49:03.117 WARN [main] c.a.FeatureFlags - Flag is deprecated"  ->  "Flag is deprecated"
    /// "java.net.SocketTimeoutException: Read timed out"                             ->  unchanged
    /// ```
    public static String message(String line) {
        String structured = structuredMessage(line);
        if (structured != null) {
            return structured;
        }
        String s = LEADING.matcher(line).replaceFirst("");
        Matcher level = LEVEL.matcher(s);
        if (level.find()) {
            s = s.substring(level.end());
        }
        int dash = s.indexOf(" - ");
        if (dash > 0 && dash < MAX_PREFIX) {
            s = s.substring(dash + 3);
        }
        s = s.strip();
        return s.isEmpty() ? line.strip() : s;
    }

    /// For a JSON or logfmt line with a message field: the message, followed by the fields that
    /// are neither time, level nor message (`order placed  status=502 order.id=ORD-1`).
    static String structuredMessage(String line) {
        String t = line.stripLeading();
        List<Field> fields = null;
        if (t.startsWith("{")) {
            fields = StructuredParser.json(line);
        } else if (t.indexOf('=') > 0 && t.indexOf('=') < 40) {
            fields = StructuredParser.logfmt(line);
        }
        if (fields == null) {
            return null;
        }
        String message = null;
        StringBuilder rest = new StringBuilder();
        for (Field f : fields) {
            String name = f.name();
            if (message == null && StructuredLines.MESSAGE_FIELDS.contains(name)) {
                message = f.value();
            } else if (!StructuredLines.TIME_FIELDS.contains(name) && !StructuredLines.LEVEL_FIELDS.contains(name)) {
                rest.append(rest.isEmpty() ? "" : " ").append(name).append('=').append(f.value());
            }
        }
        if (message == null) {
            return null;
        }
        return rest.isEmpty() ? message.strip() : message.strip() + "  " + rest;
    }
}
