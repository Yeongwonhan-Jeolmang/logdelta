// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.structured;

import io.github.logdelta.core.util.Json;

import java.util.ArrayList;
import java.util.List;

/// Splits JSON and logfmt lines into [Field]s, and detects which format a log uses.
///
/// Parsing is strict: a line that is not one complete JSON object (or not purely `key=value`
/// pairs, for logfmt) is treated as plain text, so a log that mixes structured lines with a
/// plain banner or stack trace still compares sensibly.
public final class StructuredParser {

    /// Lines sampled from each log by [#detect].
    public static final int SAMPLE_LINES = 400;
    /// Share of sampled non-blank lines that must parse for a format to be chosen.
    public static final double DETECT_RATIO = 0.6;

    private StructuredParser() {
    }

    /// Parses a line in the given format.
    ///
    /// @return the fields in line order, or `null` if the line is not in that format (always
    ///         `null` for [LogFormat#PLAIN] and [LogFormat#AUTO])
    public static List<Field> parse(String line, LogFormat format) {
        return switch (format) {
            case JSON -> json(line);
            case LOGFMT -> logfmt(line);
            case PLAIN, AUTO -> null;
        };
    }

    /// Parses a line that holds exactly one JSON object, or returns `null`.
    public static List<Field> json(String line) {
        if (line.isEmpty() || line.indexOf('{') < 0) {
            return null;
        }
        List<Field> fields = new ArrayList<>();
        boolean ok = Json.flattenObject(line,
                (path, value, keyStart, valueStart, valueEnd) ->
                        fields.add(new Field(path, value, keyStart, valueStart, valueEnd)));
        return ok ? List.copyOf(fields) : null;
    }

    /// Parses a logfmt line (`level=info msg="started server" port=8080`), or returns `null`.
    ///
    /// A line qualifies when every token is a key or a `key=value` pair and at least two tokens
    /// are pairs. Values may be double-quoted with backslash escapes. A bare key has the value
    /// `true`.
    public static List<Field> logfmt(String line) {
        List<Field> fields = new ArrayList<>();
        int pairs = 0;
        int i = 0;
        int n = line.length();
        while (i < n) {
            while (i < n && line.charAt(i) == ' ') {
                i++;
            }
            if (i >= n) {
                break;
            }
            int keyStart = i;
            if (!isKeyStart(line.charAt(i))) {
                return null;
            }
            while (i < n && isKeyPart(line.charAt(i))) {
                i++;
            }
            String key = line.substring(keyStart, i);
            if (i >= n || line.charAt(i) == ' ') {
                fields.add(new Field(key, "true", keyStart, keyStart, i));
                continue;
            }
            if (line.charAt(i) != '=') {
                return null;
            }
            i++;
            int valueStart = i;
            String value;
            if (i < n && line.charAt(i) == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    char c = line.charAt(i);
                    if (c == '\\' && i + 1 < n) {
                        char e = line.charAt(i + 1);
                        sb.append(switch (e) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            case 'r' -> '\r';
                            default -> e;
                        });
                        i += 2;
                    } else if (c == '"') {
                        i++;
                        closed = true;
                        break;
                    } else {
                        sb.append(c);
                        i++;
                    }
                }
                if (!closed || (i < n && line.charAt(i) != ' ')) {
                    return null;
                }
                value = sb.toString();
            } else {
                while (i < n && line.charAt(i) != ' ') {
                    if (line.charAt(i) == '"') {
                        return null;
                    }
                    i++;
                }
                value = line.substring(valueStart, i);
            }
            fields.add(new Field(key, value, keyStart, valueStart, i));
            pairs++;
        }
        return pairs >= 2 ? List.copyOf(fields) : null;
    }

    private static boolean isKeyStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '@';
    }

    private static boolean isKeyPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-' || c == '/' || c == '@';
    }

    /// Decides the format of logs from a sample of their lines.
    ///
    /// @param logs the lines of each log (typically baseline and candidate); up to
    ///             [#SAMPLE_LINES] non-blank lines of each are examined
    /// @return [LogFormat#JSON], [LogFormat#LOGFMT] or [LogFormat#PLAIN]
    public static LogFormat detect(List<List<String>> logs) {
        int sampled = 0;
        int json = 0;
        int logfmt = 0;
        for (List<String> lines : logs) {
            int taken = 0;
            for (int i = 0; i < lines.size() && taken < SAMPLE_LINES; i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    continue;
                }
                taken++;
                if (json(line) != null) {
                    json++;
                } else if (logfmt(line) != null) {
                    logfmt++;
                }
            }
            sampled += taken;
        }
        if (sampled == 0) {
            return LogFormat.PLAIN;
        }
        if (json >= DETECT_RATIO * sampled) {
            return LogFormat.JSON;
        }
        if (logfmt >= DETECT_RATIO * sampled) {
            return LogFormat.LOGFMT;
        }
        return LogFormat.PLAIN;
    }
}
