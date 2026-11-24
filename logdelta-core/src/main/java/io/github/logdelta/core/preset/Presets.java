// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.preset;

import io.github.logdelta.core.normalize.Rule;
import io.github.logdelta.core.normalize.RuleCategory;
import io.github.logdelta.core.normalize.RuleSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/// The catalogue of [Preset]s and their auto-detection.
///
/// | Id | Recognises |
/// |---|---|
/// | `spring-boot` | Spring Boot's default console format |
/// | `access-log` | nginx and Apache access logs (common and combined formats) |
/// | `kubernetes` | container logs in the CRI format (containerd, CRI-O) and klog lines of Kubernetes components |
/// | `journald` | `journalctl` output and classic syslog files |
/// | `logcat` | Android `adb logcat` (threadtime and brief formats) |
/// | `python` | Python's `logging` module defaults and common formats |
///
/// [#detect] looks at up to [#SAMPLE_LINES] non-blank lines of each log and picks the preset
/// that matches the most of them, if it matches at least [#DETECT_RATIO] of them.
public final class Presets {

    /// Value of the `preset` option that turns on detection.
    public static final String AUTO = "auto";
    /// Value of the `preset` option that turns presets off.
    public static final String NONE = "none";
    /// Lines sampled from each log.
    public static final int SAMPLE_LINES = 400;
    /// Minimum share of sampled lines a preset must match.
    public static final double DETECT_RATIO = 0.5;

    private static final String LEVEL = "(?:TRACE|DEBUG|INFO|WARN|ERROR|FATAL)";
    private static final List<Preset> ALL = build();

    private Presets() {
    }

    /// Every preset, in detection priority order.
    public static List<Preset> all() {
        return ALL;
    }

    /// The preset with the given id, or `null`.
    public static Preset find(String id) {
        for (Preset p : ALL) {
            if (p.id().equalsIgnoreCase(id.strip())) {
                return p;
            }
        }
        return null;
    }

    /// The preset rule with the given id, or `null`.
    public static Rule findRule(String ruleId) {
        for (Preset p : ALL) {
            for (Rule r : p.rules()) {
                if (r.id().equals(ruleId)) {
                    return r;
                }
            }
        }
        return null;
    }

    /// Validates a `preset` option value: `auto`, `none` or a preset id.
    ///
    /// @return the value in canonical (lower-case) form
    /// @throws IllegalArgumentException for unknown values
    public static String parseOption(String value) {
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (v.equals(AUTO) || v.equals(NONE) || find(v) != null) {
            return v;
        }
        StringBuilder ids = new StringBuilder();
        for (Preset p : ALL) {
            ids.append(", ").append(p.id());
        }
        throw new IllegalArgumentException("Unknown preset '" + value + "' (expected auto, none" + ids + ")");
    }

    /// Picks the preset that best fits the logs, or returns `null`.
    public static Preset detect(List<List<String>> logs) {
        int[] hits = new int[ALL.size()];
        int sampled = 0;
        for (List<String> lines : logs) {
            int taken = 0;
            for (int i = 0; i < lines.size() && taken < SAMPLE_LINES; i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    continue;
                }
                taken++;
                for (int p = 0; p < ALL.size(); p++) {
                    if (ALL.get(p).matches(line)) {
                        hits[p]++;
                    }
                }
            }
            sampled += taken;
        }
        int best = -1;
        for (int p = 0; p < hits.length; p++) {
            if (hits[p] >= DETECT_RATIO * sampled && sampled > 0 && (best < 0 || hits[p] > hits[best])) {
                best = p;
            }
        }
        return best < 0 ? null : ALL.get(best);
    }

    /// Adds the preset's rules in front of `rules`. Rules whose id is already present (for
    /// example because a profile disabled one) are left as they are.
    public static RuleSet apply(Preset preset, RuleSet rules) {
        RuleSet out = rules;
        int at = 0;
        for (Rule r : preset.rules()) {
            if (out.find(r.id()) == null) {
                out = out.withAt(at, r);
            }
            at = out.indexOf(r.id()) + 1;
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------

    private static List<Preset> build() {
        List<Preset> out = new ArrayList<>();

        out.add(new Preset("spring-boot", "Spring Boot",
                "Spring Boot console logs: timestamp, level, pid, --- [thread] logger : message",
                Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}[.,]\\d{3}\\S*\\s+" + LEVEL
                        + "\\s+\\d+\\s+---\\s+\\["),
                List.of(
                        rule("spring-boot.pid", "Spring Boot process id",
                                "(?<=\\b" + LEVEL + "\\s{1,5})\\d+(?=\\s+---\\s)", "<PID>",
                                "The pid between the level and '---'"),
                        rule("spring-boot.reactor-thread", "Reactor and scheduler threads",
                                "\\b(reactor-http-nio|ctor-http-nio|boundedElastic|parallel|task|scheduling"
                                        + "|lettuce-nioEventLoop-\\d+|HikariPool-\\d+ housekeeper)-\\d+\\b",
                                "$1-<N>", "reactor-http-nio-3, boundedElastic-12, task-4"),
                        rule("spring-boot.uptime", "Process uptime",
                                "(?<=(?:process|JVM) running for )\\d+(?:\\.\\d+)?", "<N>",
                                "Started App in 3.2 seconds (process running for 3.9)"))));

        out.add(new Preset("access-log", "nginx / Apache access log",
                "Web server access logs in the common or combined format",
                Pattern.compile("^\\S+ \\S+ \\S+ \\[\\d{2}/[A-Z][a-z]{2}/\\d{4}:\\d{2}:\\d{2}:\\d{2} [+-]\\d{4}\\] "
                        + "\"[^\"]*\" \\d{3} (?:\\d+|-)"),
                List.of(
                        rule("access-log.bytes", "Response size",
                                "(?<=\" \\d{3} )\\d+\\b", "<BYTES>",
                                "The byte count after the status code"),
                        rule("access-log.timing", "Request and upstream times",
                                "\\b((?:u?rt|request_time|upstream_response_time|upstream_connect_time"
                                        + "|upstream_header_time|uct|uht)=)\"?\\d+(?:\\.\\d+)?\"?",
                                "$1<RT>", "rt=0.123 urt=\"0.120\""),
                        rule("access-log.trailing-time", "Trailing request time",
                                "(?<=\" )\\d+\\.\\d{3}$", "<RT>",
                                "nginx's $request_time when it is the last field"),
                        rule("access-log.cache-buster", "Cache-busting query parameters",
                                "([?&](?:_|t|ts|cb|cachebuster|nocache|rnd|rand)=)[^&\\s\"]+", "$1<CB>",
                                "/app.js?_=1762955343123"))));

        out.add(new Preset("kubernetes", "Kubernetes",
                "Container logs in the CRI format (containerd, CRI-O) and klog lines of Kubernetes components",
                Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2}) "
                        + "(?:stdout|stderr) [FP] |^[IWEF]\\d{4} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}\\s+\\d+ [\\w.-]+:\\d+\\] "),
                List.of(
                        rule("kubernetes.cri-prefix", "CRI log prefix",
                                "^\\d{4}-\\d{2}-\\d{2}T\\S+ (stdout|stderr) [FP] ", "<TIMESTAMP> $1 ",
                                "2026-11-12T08:49:03.123456789Z stdout F (the F/P flag marks partial lines)"),
                        rule("kubernetes.klog-header", "klog header",
                                "^([IWEF])\\d{4} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}\\s+\\d+ ", "$1<TIMESTAMP> <PID> ",
                                "I1112 08:49:03.123456   12345 controller.go:123]"),
                        rule("kubernetes.node-name", "Cloud node name",
                                "\\bip-\\d{1,3}-\\d{1,3}-\\d{1,3}-\\d{1,3}(?:\\.[a-z0-9-]+)*\\.internal\\b|"
                                        + "\\bgke-[a-z0-9-]+-[0-9a-f]{8}-[a-z0-9]{4}\\b|\\baks-[a-z0-9]+-\\d{8}-vmss[0-9a-z]{6}\\b",
                                "<NODE>", "ip-10-0-1-23.ec2.internal, gke-prod-pool-1-3f2b8c1e-x7q2"),
                        rule("kubernetes.resource-version", "Resource version",
                                "\\b(resourceVersion[\"']?\\s*[=:]\\s*[\"']?)\\d+", "$1<N>",
                                "resourceVersion: 123456"))));

        out.add(new Preset("journald", "journald / syslog",
                "journalctl output and syslog files: timestamp, host, unit[pid]: message",
                Pattern.compile("^(?:[A-Z][a-z]{2} [ \\d]\\d \\d{2}:\\d{2}:\\d{2}(?:\\.\\d{6})?"
                        + "|\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?[+-]\\d{2}:?\\d{2}) "
                        + "[\\w.-]+ [^\\s:\\[]+(?:\\[\\d+\\])?: "),
                List.of(
                        rule("journald.host", "Host name",
                                "(?<=^[A-Z][a-z]{2} [ \\d]\\d \\d{2}:\\d{2}:\\d{2}(?:\\.\\d{6})? )[\\w.-]+(?= )",
                                "<HOST>", "The host after a syslog timestamp"),
                        rule("journald.host-iso", "Host name (ISO timestamps)",
                                "(?<=^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?[+-]\\d{2}:?\\d{2} )"
                                        + "[\\w.-]+(?= )",
                                "<HOST>", "The host after journalctl -o short-iso timestamps"),
                        rule("journald.kernel-uptime", "Kernel uptime",
                                "(?<=kernel: )\\[\\s*\\d+\\.\\d+\\]", "[<UPTIME>]", "kernel: [ 12.345678]"),
                        rule("journald.session", "Login session number",
                                "(?<=\\b[Ss]ession )\\d+(?= of [Uu]ser)|(?<=\\bsession-)\\d+(?=\\.scope)", "<N>",
                                "Started Session 42 of User bob, session-42.scope"))));

        out.add(new Preset("logcat", "Android logcat",
                "adb logcat output in the threadtime (default) or brief format",
                Pattern.compile("^(?:\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\d+\\s+\\d+ [VDIWEFA] "
                        + "|[VDIWEFA]/[^(\\s]+\\(\\s*\\d+\\): )"),
                List.of(
                        rule("logcat.ids", "Process and thread ids",
                                "(?<=^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\d+\\s+\\d+(?= [VDIWEFA] )",
                                " <PID> <TID>", "The two numbers before the priority letter"),
                        rule("logcat.date", "Month and day",
                                "^\\d{2}-\\d{2}(?= \\d{2}:\\d{2}:\\d{2}\\.\\d{3})", "<DATE>", "11-12 at the start"),
                        rule("logcat.brief-pid", "Process id (brief format)",
                                "(?<=^[VDIWEFA]/[^(\\s]{1,100})\\(\\s*\\d+\\)(?=:)", "(<PID>)", "E/Tag( 1234):"))));

        out.add(new Preset("python", "Python logging",
                "Python's logging module: LEVEL:logger:message or asctime - name - LEVEL - message",
                Pattern.compile("^(?:(?:CRITICAL|ERROR|WARNING|INFO|DEBUG):[\\w.]*:|\\d{4}-\\d{2}-\\d{2} "
                        // "asctime LEVEL" alone is also log4j's default, so only the " - name - " form counts.
                        + "\\d{2}:\\d{2}:\\d{2},\\d{3} - [\\w.]+ - (?:CRITICAL|ERROR|WARNING|INFO|DEBUG) - "
                        + "|Traceback \\(most recent call last\\):|  File \"[^\"]+\", line \\d+)"),
                List.of(
                        rule("python.worker", "Worker and process names",
                                "\\b(ForkPoolWorker-|SpawnPoolWorker-|Process-|ThreadPoolExecutor-\\d+_|Task-)\\d+\\b",
                                "$1<N>", "ForkPoolWorker-3, ThreadPoolExecutor-0_2, Task-17"),
                        rule("python.server-pid", "Server process id",
                                "(?<=(?:server|worker|reloader) process \\[)\\d+(?=\\])", "<PID>",
                                "Started server process [12345]"))));

        return List.copyOf(out);
    }

    private static Rule rule(String id, String name, String regex, String replacement, String description) {
        return Rule.builder(id).name(name).category(RuleCategory.FORMAT).pattern(regex).replacement(replacement)
                .description(description).builtin(true).build();
    }
}
