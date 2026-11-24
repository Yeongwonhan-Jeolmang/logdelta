// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.analysis;

import io.github.logdelta.core.model.ProcessedLine;
import io.github.logdelta.core.structured.StructuredLines;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Guesses the severity of a log line.
///
/// Detection, in order:
///
/// 1. the level field of a structured (JSON or logfmt) line, see [#detect(ProcessedLine)];
/// 1. the priority letter of Android logcat (`11-12 08:49:03.123  1234  5678 E Tag: ...`) and
///    the severity letter of Kubernetes' klog (`E1112 08:49:03.123456 ...`);
/// 1. an upper-case level keyword such as `ERROR`, `WARN` or `SEVERE`;
/// 1. a structured field such as `level=error` or `"level":"warn"`;
/// 1. stack-trace and exception markers (`at com.x.Y(...)`, `Caused by:`,
///    `SomethingException`, `Traceback`, `panic:`), reported as ERROR.
///
/// Anything else is [Severity#NONE].
public final class SeverityDetector {

    private static final Pattern KEYWORD = Pattern.compile(
            "\\b(FATAL|CRIT(?:ICAL)?|EMERG(?:ENCY)?|ALERT|SEVERE|ERROR|ERR|WARN(?:ING)?|NOTICE|INFO|DEBUG|FINE[RST]*|TRACE)\\b");
    private static final Pattern FIELD = Pattern.compile(
            "(?i)\\b(?:level|severity|lvl)\"?\\s*[=:]\\s*\"?([a-z]+)");
    private static final Pattern EXCEPTION = Pattern.compile(
            "^\\s+at\\s+[\\w$.<>/]+\\(|^Caused by:|^Traceback \\(most recent call last\\)|^\\s*panic:"
                    + "|^Exception in thread|\\b[\\w$.]*[\\w$](?:Exception|Error)\\b");

    private static final Pattern LETTER = Pattern.compile(
            "^(?:\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\d+\\s+\\d+ ([VDIWEFA]) "
                    + "|([VDIWEFA])/[^(\\s]+\\(\\s*\\d+\\): |([IWEF])\\d{4} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}\\s)");

    private SeverityDetector() {
    }

    /// Returns the severity of a processed line: from its level field if it is structured,
    /// otherwise from its text.
    public static Severity detect(ProcessedLine line) {
        if (line.isStructured()) {
            String level = StructuredLines.level(line);
            if (level != null) {
                Severity s = fromWord(level.toUpperCase(Locale.ROOT));
                if (s != Severity.NONE) {
                    return s;
                }
                Severity numeric = fromNumber(level);
                if (numeric != Severity.NONE) {
                    return numeric;
                }
            }
        }
        return detect(line.text());
    }

    /// Returns the detected severity of `line`.
    public static Severity detect(String line) {
        if (!line.isEmpty() && Character.isLetterOrDigit(line.charAt(0))) {
            Matcher l = LETTER.matcher(line);
            if (l.lookingAt()) {
                String letter = l.group(1) != null ? l.group(1) : l.group(2) != null ? l.group(2) : l.group(3);
                Severity s = fromLetter(letter.charAt(0));
                if (s != Severity.NONE) {
                    return s;
                }
            }
        }
        Matcher m = KEYWORD.matcher(line);
        if (m.find()) {
            return fromWord(m.group(1));
        }
        m = FIELD.matcher(line);
        if (m.find()) {
            Severity s = fromWord(m.group(1).toUpperCase(Locale.ROOT));
            if (s != Severity.NONE) {
                return s;
            }
        }
        return EXCEPTION.matcher(line).find() ? Severity.ERROR : Severity.NONE;
    }

    private static Severity fromLetter(char c) {
        return switch (c) {
            case 'V' -> Severity.TRACE;
            case 'D' -> Severity.DEBUG;
            case 'I' -> Severity.INFO;
            case 'W' -> Severity.WARN;
            case 'E' -> Severity.ERROR;
            case 'F', 'A' -> Severity.FATAL;
            default -> Severity.NONE;
        };
    }

    /// Numeric levels: pino/bunyan (10 trace ... 60 fatal) and syslog priorities are not
    /// distinguishable in general, so only the pino/bunyan scale is recognised.
    private static Severity fromNumber(String level) {
        return switch (level.strip()) {
            case "10" -> Severity.TRACE;
            case "20" -> Severity.DEBUG;
            case "30" -> Severity.INFO;
            case "40" -> Severity.WARN;
            case "50" -> Severity.ERROR;
            case "60" -> Severity.FATAL;
            default -> Severity.NONE;
        };
    }

    private static Severity fromWord(String w) {
        if (w.startsWith("FINE")) {
            return Severity.DEBUG;
        }
        return switch (w) {
            case "FATAL", "CRIT", "CRITICAL", "EMERG", "EMERGENCY", "ALERT", "PANIC" -> Severity.FATAL;
            case "SEVERE", "ERROR", "ERR" -> Severity.ERROR;
            case "WARN", "WARNING" -> Severity.WARN;
            case "NOTICE", "INFO", "INFORMATION" -> Severity.INFO;
            case "DEBUG" -> Severity.DEBUG;
            case "TRACE" -> Severity.TRACE;
            default -> Severity.NONE;
        };
    }
}
