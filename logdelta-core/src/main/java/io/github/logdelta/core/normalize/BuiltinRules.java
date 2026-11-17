// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.normalize;

import java.util.ArrayList;
import java.util.List;

/// The catalogue of rules that ship with LogDelta.
///
/// Order matters: rules run top to bottom and a later rule never re-matches text that an
/// earlier rule already masked. Specific formats therefore come before generic ones (full
/// timestamps before bare times, UUIDs before hex hashes, and so on).
///
/// Rules that tend to hide real differences (bare numbers, sizes) are included but disabled
/// by default.
public final class BuiltinRules {

    private static final String MONTHS = "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)";

    private static final List<Rule> ALL = build();

    private BuiltinRules() {
    }

    /// All built-in rules in evaluation order, with their default enabled state.
    public static List<Rule> all() {
        return ALL;
    }

    /// Returns the built-in rule with the given id, or `null`.
    public static Rule find(String id) {
        for (Rule r : ALL) {
            if (r.id().equals(id)) {
                return r;
            }
        }
        return null;
    }

    private static List<Rule> build() {
        List<Rule> r = new ArrayList<>();

        // --- Timestamps --------------------------------------------------------------------
        r.add(rule("iso-timestamp", "ISO-8601 timestamp", RuleCategory.TIMESTAMP,
                "\\b\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(?::\\d{2}(?:[.,]\\d{1,9})?)?(?:Z|[+-]\\d{2}:?\\d{2})?",
                "<TIMESTAMP>", true, "2026-11-12T08:49:03.123Z, 2026-11-12 08:49:03,123"));
        r.add(rule("syslog-timestamp", "Syslog timestamp", RuleCategory.TIMESTAMP,
                "\\b" + MONTHS + "\\s{1,2}\\d{1,2}\\s\\d{2}:\\d{2}:\\d{2}\\b",
                "<TIMESTAMP>", true, "Nov 12 08:49:03"));
        r.add(rule("clf-timestamp", "Apache/NCSA timestamp", RuleCategory.TIMESTAMP,
                "\\b\\d{2}/" + MONTHS + "/\\d{4}:\\d{2}:\\d{2}:\\d{2}\\s[+-]\\d{4}\\b",
                "<TIMESTAMP>", true, "12/Nov/2026:08:49:03 -0500"));
        r.add(rule("date", "Calendar date", RuleCategory.TIMESTAMP,
                "\\b(?:\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{4})\\b",
                "<DATE>", true, "2026-11-12, 11/12/2026, 12.11.2026"));
        r.add(rule("time", "Time of day", RuleCategory.TIMESTAMP,
                "\\b\\d{1,2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?\\b",
                "<TIME>", true, "08:49:03.123"));
        r.add(rule("epoch", "Unix epoch timestamp", RuleCategory.TIMESTAMP,
                "\\b1[5-9]\\d{8}(?:\\d{3}){0,2}\\b",
                "<EPOCH>", true, "1762955343, 1762955343123 (seconds, millis or micros since 2017)"));

        // --- Identifiers -------------------------------------------------------------------
        r.add(rule("uuid", "UUID", RuleCategory.IDENTIFIER,
                "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b",
                "<UUID>", true, "3f2b8c1e-9a4d-4e7b-8c2a-1d5e6f7a8b9c"));
        r.add(builder("trace-id", "Trace / request id", RuleCategory.IDENTIFIER,
                "\\b((?:trace|span|request|req|correlation|session|txn|transaction)[_-]?id\\s*[=:]\\s*)[\"']?[\\w.:-]+[\"']?",
                "$1<ID>", true, "traceId=4bf92f3577b34da6, request_id: abc-123")
                .caseInsensitive(true).build());
        r.add(rule("hash", "Hex digest (MD5/SHA)", RuleCategory.IDENTIFIER,
                "\\b(?:[0-9a-fA-F]{64}|[0-9a-fA-F]{40}|[0-9a-fA-F]{32})\\b",
                "<HASH>", true, "32, 40 or 64 hex characters"));
        r.add(rule("hex-id", "Long hex identifier", RuleCategory.IDENTIFIER,
                "\\b(?=[0-9a-f]*[a-f])(?=[0-9a-f]*\\d)[0-9a-f]{12,}\\b",
                "<HEX>", true, "Container ids, commit prefixes: 4bf92f3577b3"));
        r.add(rule("k8s-pod", "Kubernetes pod suffix", RuleCategory.IDENTIFIER,
                "(?<=[a-z0-9]-)(?=[a-z]{0,9}\\d)[a-z0-9]{8,10}-[a-z0-9]{5}\\b",
                "<POD>", true, "checkout-7d9f8b6c5d-x2x7q"));

        // --- Memory ------------------------------------------------------------------------
        r.add(rule("hex-address", "Memory address", RuleCategory.MEMORY,
                "\\b0x[0-9a-fA-F]{4,16}\\b",
                "<ADDR>", true, "0x7ffd5e8c3a10"));
        r.add(rule("object-identity", "Object identity hash", RuleCategory.MEMORY,
                "(?<=[\\w$\\]])@[0-9a-f]{5,8}\\b",
                "@<ID>", true, "com.example.Order@1b6d3586"));
        r.add(rule("size", "Byte sizes", RuleCategory.MEMORY,
                "\\b\\d+(?:\\.\\d+)?\\s?(?:[KMGT]i?B|bytes)\\b",
                "<SIZE>", false, "512MB, 1.5 GiB, 2048 bytes"));

        // --- Processes & threads -----------------------------------------------------------
        r.add(rule("thread-name", "Numbered thread name", RuleCategory.PROCESS,
                "\\b(pool-\\d+-thread-|[Tt]hread-|worker-|exec-|ForkJoinPool[\\w.-]*-worker-|nioEventLoopGroup-\\d+-)\\d+\\b",
                "$1<N>", true, "pool-1-thread-7, http-nio-8080-exec-3, Thread-12"));
        r.add(builder("pid", "Process / thread id", RuleCategory.PROCESS,
                "\\b((?:pid|ppid|tid|thread|thread[_-]?id|process)\\s*[=:#]\\s*)\\d+\\b",
                "$1<N>", true, "pid=4242, tid: 17, thread #9")
                .caseInsensitive(true).build());
        r.add(rule("syslog-pid", "Syslog process id", RuleCategory.PROCESS,
                "(?<=[\\w-])\\[\\d+\\](?=:)",
                "[<PID>]", true, "sshd[1234]:"));

        // --- Network -----------------------------------------------------------------------
        r.add(rule("ipv4", "IPv4 address", RuleCategory.NETWORK,
                "\\b(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}\\b",
                "<IP>", true, "10.0.12.7"));
        r.add(builder("ipv6", "IPv6 address", RuleCategory.NETWORK,
                "\\b(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}\\b",
                "<IP>", true, "Full (uncompressed) form only: 2001:0db8:0000:0000:0000:ff00:0042:8329")
                .caseInsensitive(true).build());
        r.add(rule("ephemeral-port", "Ephemeral port", RuleCategory.NETWORK,
                "(?<=:)(?:49[2-9]\\d{2}|5\\d{4}|6[0-4]\\d{3}|65[0-4]\\d{2}|655[0-2]\\d|6553[0-5])\\b",
                "<PORT>", true, "Client-side ports 49200-65535 after a colon: 10.0.0.1:53412"));

        // --- Metrics -----------------------------------------------------------------------
        r.add(rule("duration", "Duration", RuleCategory.METRIC,
                "\\b\\d+(?:\\.\\d+)?\\s?(?:ns|us|\u00b5s|ms|s|secs?|seconds?|mins?|minutes?)\\b",
                "<DURATION>", true, "took 132ms, 1.5 s, 3 seconds"));
        r.add(rule("number", "Any number", RuleCategory.METRIC,
                "\\b\\d+(?:\\.\\d+)?\\b",
                "<N>", false, "Aggressive: masks every remaining number"));

        // --- Paths -------------------------------------------------------------------------
        r.add(rule("temp-path", "Temporary path", RuleCategory.PATH,
                "(?:/tmp|/var/folders|/private/var/folders)/[^\\s:'\",;)\\]]+"
                        + "|\\b[A-Za-z]:\\\\Users\\\\[^\\\\\\s]+\\\\AppData\\\\Local\\\\Temp\\\\[^\\s:'\",;)\\]]+",
                "<TMPDIR>", true, "/tmp/junit8812/out.txt, C:\\Users\\me\\AppData\\Local\\Temp\\x.tmp"));

        return List.copyOf(r);
    }

    private static Rule rule(String id, String name, RuleCategory category, String regex,
                             String replacement, boolean enabled, String description) {
        return builder(id, name, category, regex, replacement, enabled, description).build();
    }

    private static Rule.Builder builder(String id, String name, RuleCategory category, String regex,
                                        String replacement, boolean enabled, String description) {
        return Rule.builder(id).name(name).category(category).pattern(regex).replacement(replacement)
                .enabled(enabled).builtin(true).description(description);
    }
}
