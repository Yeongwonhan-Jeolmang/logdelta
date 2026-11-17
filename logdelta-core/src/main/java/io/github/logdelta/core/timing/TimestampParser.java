// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.timing;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Finds the timestamp of a log line and converts it to milliseconds.
///
/// Only the **difference** between timestamps matters to LogDelta, so time zones are ignored
/// and every value is read as if it were UTC. Recognised formats, tried in this order:
///
/// - ISO-8601 style dates with a time, anywhere in the first 120 characters:
///   `2026-11-12T08:49:03.123Z`, `2026-11-12 08:49:03,123`, `2026/11/12 08:49:03`
/// - Apache/NCSA: `12/Nov/2026:08:49:03 -0500`
/// - syslog, near the start of the line: `Nov 12 08:49:03` (the year is unknown, so 2000 is used)
/// - a bare time at the start of the line, optionally in brackets: `08:49:03.117`
///   (milliseconds since midnight; [Timeline] handles lines that cross midnight)
/// - Unix epoch seconds or milliseconds at the start of the line: `1762955343.117`
///
/// Instances are stateless; the methods are thread-safe.
public final class TimestampParser {

    /// Returned when a line has no recognisable timestamp.
    public static final long NONE = Long.MIN_VALUE;

    private static final int SEARCH_WINDOW = 120;
    private static final String MONTHS = "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec";

    private static final Pattern ISO = Pattern.compile(
            "(\\d{4})[-/](\\d{2})[-/](\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?");
    private static final Pattern CLF = Pattern.compile(
            "(\\d{2})/(" + MONTHS + ")/(\\d{4}):(\\d{2}):(\\d{2}):(\\d{2})");
    private static final Pattern SYSLOG = Pattern.compile(
            "^.{0,24}?\\b(" + MONTHS + ")\\s{1,2}(\\d{1,2}) (\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?");
    private static final Pattern TIME = Pattern.compile("^\\[?(\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?\\b");
    private static final Pattern EPOCH = Pattern.compile("^\\[?(1[5-9]\\d{8})(?:(\\d{3})|\\.(\\d{1,6}))?\\b");

    private TimestampParser() {
    }

    /// Returns the timestamp of `line` in milliseconds, or [#NONE].
    public static long parse(String line) {
        if (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
            return NONE; // continuation lines (stack frames, wrapped messages) never start a timestamp
        }
        int end = Math.min(line.length(), SEARCH_WINDOW);
        Matcher m = ISO.matcher(line).region(0, end);
        if (m.find()) {
            return dateTime(num(m, 1), num(m, 2), num(m, 3), num(m, 4), num(m, 5), num(m, 6), m.group(7));
        }
        m = CLF.matcher(line).region(0, end);
        if (m.find()) {
            return dateTime(num(m, 3), month(m.group(2)), num(m, 1), num(m, 4), num(m, 5), num(m, 6), null);
        }
        m = SYSLOG.matcher(line).region(0, end);
        if (m.find()) {
            return dateTime(2000, month(m.group(1)), num(m, 2), num(m, 3), num(m, 4), num(m, 5), m.group(6));
        }
        m = TIME.matcher(line).region(0, end);
        if (m.find()) {
            int h = num(m, 1);
            int min = num(m, 2);
            int s = num(m, 3);
            if (h > 23 || min > 59 || s > 60) {
                return NONE;
            }
            return ((h * 60L + min) * 60 + s) * 1000 + millis(m.group(4));
        }
        m = EPOCH.matcher(line).region(0, end);
        if (m.find()) {
            long seconds = Long.parseLong(m.group(1));
            if (m.group(2) != null) {
                return seconds * 1000 + Integer.parseInt(m.group(2));
            }
            return seconds * 1000 + millis(m.group(3));
        }
        return NONE;
    }

    private static long dateTime(int y, int mo, int d, int h, int mi, int s, String fraction) {
        try {
            long seconds = LocalDateTime.of(y, mo, d, h, mi, Math.min(s, 59)).toEpochSecond(ZoneOffset.UTC);
            return seconds * 1000 + millis(fraction);
        } catch (DateTimeException e) {
            return NONE;
        }
    }

    private static int num(Matcher m, int group) {
        return Integer.parseInt(m.group(group));
    }

    private static int millis(String fraction) {
        if (fraction == null || fraction.isEmpty()) {
            return 0;
        }
        String f = fraction.length() >= 3 ? fraction.substring(0, 3) : (fraction + "00").substring(0, 3);
        return Integer.parseInt(f);
    }

    private static int month(String name) {
        return switch (name) {
            case "Jan" -> 1;
            case "Feb" -> 2;
            case "Mar" -> 3;
            case "Apr" -> 4;
            case "May" -> 5;
            case "Jun" -> 6;
            case "Jul" -> 7;
            case "Aug" -> 8;
            case "Sep" -> 9;
            case "Oct" -> 10;
            case "Nov" -> 11;
            case "Dec" -> 12;
            default -> throw new IllegalArgumentException(name);
        };
    }
}
