// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.analysis;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Guesses the severity of a log line.
///
/// Detection, in order:
///
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

    private SeverityDetector() {
    }

    /// Returns the detected severity of `line`.
    public static Severity detect(String line) {
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
