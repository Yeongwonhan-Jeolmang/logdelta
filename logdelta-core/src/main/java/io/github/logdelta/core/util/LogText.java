// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.util;

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
}
