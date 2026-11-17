// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.view;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Finds the structural parts of a log line so that they can be coloured: the timestamp, the
/// level, the thread and the logger name, plus stack-trace frames and exception names.
///
/// The highlighter is deliberately conservative. It only looks at the *prefix* of a line, in
/// the order these fields appear in common layouts (Logback, Log4j, Spring Boot, syslog,
/// java.util.logging, Python), and colours nothing it is unsure about, so message text is never
/// mistaken for structure.
///
/// The result is one style byte per character, one of the constants below.
public final class SyntaxHighlighter {

    /// No special style.
    public static final byte PLAIN = 0;
    /// Timestamp or time of day.
    public static final byte TIMESTAMP = 1;
    /// ERROR, FATAL, SEVERE and similar levels, and exception class names.
    public static final byte LEVEL_ERROR = 2;
    /// WARN and WARNING.
    public static final byte LEVEL_WARN = 3;
    /// INFO and NOTICE.
    public static final byte LEVEL_INFO = 4;
    /// DEBUG, TRACE and FINE*.
    public static final byte LEVEL_DEBUG = 5;
    /// Logger or category name, e.g. `c.a.checkout.PaymentClient`.
    public static final byte LOGGER = 6;
    /// Thread name in brackets, e.g. `[main]`.
    public static final byte THREAD = 7;
    /// A stack-trace frame (`at com.example.Foo.bar(Foo.java:12)`).
    public static final byte STACK = 8;

    private static final int PREFIX_LIMIT = 160;

    private static final Pattern TIMESTAMP_START = Pattern.compile(
            "^\\s*\\[?(?:\\d{4}[-/]\\d{2}[-/]\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:\\s?Z|\\s?[+-]\\d{2}:?\\d{2})?"
                    + "|[A-Z][a-z]{2} [ \\d]\\d \\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?"
                    + "|\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,6})?"
                    + "|\\d{10}(?:\\.\\d{1,9})?|\\d{13})\\]?");
    private static final Pattern TIMESTAMP_ANYWHERE = Pattern.compile(
            "\\[\\d{2}/[A-Z][a-z]{2}/\\d{4}:\\d{2}:\\d{2}:\\d{2} [+-]\\d{4}\\]"
                    + "|\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?");
    private static final Pattern LEVEL = Pattern.compile(
            "\\b(FATAL|CRIT(?:ICAL)?|EMERG|ALERT|SEVERE|ERROR|ERR|WARN(?:ING)?|NOTICE|INFO|DEBUG|TRACE|FINE[RST]*)\\b");
    private static final Pattern DOTTED = Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+");
    private static final Pattern SIMPLE_NAME = Pattern.compile("[A-Za-z_][\\w.$-]*");
    private static final Pattern FRAME = Pattern.compile("^\\s+at\\s");
    private static final Pattern EXCEPTION = Pattern.compile(
            "^(?:Caused by: |Suppressed: |\\s*)?((?:[a-zA-Z_$][\\w$]*\\.)*[A-Z][\\w$]*(?:Exception|Error|Throwable))\\b");

    private SyntaxHighlighter() {
    }

    /// Computes the style of every character of `line`.
    public static byte[] styles(String line) {
        byte[] out = new byte[line.length()];
        if (line.isEmpty()) {
            return out;
        }
        if (FRAME.matcher(line).find()) {
            java.util.Arrays.fill(out, STACK);
            return out;
        }
        Matcher ex = EXCEPTION.matcher(line);
        if (ex.find()) {
            fill(out, ex.start(1), ex.end(1), LEVEL_ERROR);
            return out;
        }

        int limit = Math.min(line.length(), PREFIX_LIMIT);
        int pos = 0;
        Matcher ts = TIMESTAMP_START.matcher(line).region(0, limit);
        if (ts.lookingAt()) {
            fill(out, ts.start() + leadingSpace(line), ts.end(), TIMESTAMP);
            pos = ts.end();
        } else {
            Matcher any = TIMESTAMP_ANYWHERE.matcher(line).region(0, limit);
            if (any.find()) {
                fill(out, any.start(), any.end(), TIMESTAMP);
                pos = any.end();
            }
        }

        Matcher lv = LEVEL.matcher(line).region(pos, Math.min(line.length(), pos + 60));
        if (!lv.find()) {
            return out;
        }
        // Brackets between the timestamp and the level are thread names (Logback's default layout).
        markBrackets(line, out, pos, lv.start());
        fill(out, lv.start(), lv.end(), levelStyle(lv.group(1)));
        pos = lv.end();

        // After the level: optional [thread], pid and "---", then the logger.
        for (int tokens = 0; tokens < 5 && pos < limit; tokens++) {
            while (pos < line.length() && Character.isWhitespace(line.charAt(pos))) {
                pos++;
            }
            if (pos >= line.length()) {
                break;
            }
            char c = line.charAt(pos);
            if (c == '[') {
                int close = line.indexOf(']', pos);
                if (close < 0 || close - pos > 80) {
                    break;
                }
                fill(out, pos, close + 1, THREAD);
                pos = close + 1;
                continue;
            }
            int end = pos;
            while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
                end++;
            }
            String token = line.substring(pos, end);
            if (token.chars().allMatch(Character::isDigit) || token.equals("---") || token.equals("-")
                    || token.equals(":") || token.equals("|")) {
                pos = end;
                continue;
            }
            String bare = token.endsWith(":") ? token.substring(0, token.length() - 1) : token;
            boolean dotted = DOTTED.matcher(bare).matches();
            boolean named = !dotted && SIMPLE_NAME.matcher(bare).matches()
                    && (token.endsWith(":") || followedBySeparator(line, end));
            if (dotted || named) {
                fill(out, pos, pos + bare.length(), LOGGER);
            }
            break;
        }
        return out;
    }

    private static boolean followedBySeparator(String line, int end) {
        int i = end;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i + 1 < line.length() && (line.charAt(i) == '-' || line.charAt(i) == ':')
                && line.charAt(i + 1) == ' ';
    }

    private static void markBrackets(String line, byte[] out, int from, int to) {
        int i = from;
        while (i < to) {
            int open = line.indexOf('[', i);
            if (open < 0 || open >= to) {
                return;
            }
            int close = line.indexOf(']', open);
            if (close < 0 || close >= to) {
                return;
            }
            fill(out, open, close + 1, THREAD);
            i = close + 1;
        }
    }

    private static int leadingSpace(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i;
    }

    static byte levelStyle(String level) {
        return switch (level) {
            case "FATAL", "CRIT", "CRITICAL", "EMERG", "ALERT", "SEVERE", "ERROR", "ERR" -> LEVEL_ERROR;
            case "WARN", "WARNING" -> LEVEL_WARN;
            case "INFO", "NOTICE" -> LEVEL_INFO;
            default -> LEVEL_DEBUG;
        };
    }

    private static void fill(byte[] out, int from, int to, byte style) {
        for (int i = Math.max(0, from); i < Math.min(out.length, to); i++) {
            out[i] = style;
        }
    }
}
