// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.config;

import io.github.logdelta.core.Version;
import io.github.logdelta.core.diff.Algorithm;
import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.normalize.BuiltinRules;
import io.github.logdelta.core.normalize.InvalidRuleException;
import io.github.logdelta.core.normalize.Rule;
import io.github.logdelta.core.normalize.RuleCategory;
import io.github.logdelta.core.normalize.RuleSet;
import io.github.logdelta.core.preset.Presets;
import io.github.logdelta.core.structured.LogFormat;
import io.github.logdelta.core.timing.TimeWindow;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Reads and writes profiles: plain-text files (`*.ldprofile`) that store
/// [DiffOptions], including custom rules, so that a setup can be shared between the CLI,
/// the GUI and a team.
///
/// ## Format
///
/// ```
/// # Lines starting with '#' or ';' are comments.
/// [options]
/// # myers | patience
/// algorithm = patience
/// ignore-case = false
/// ignore-whitespace = true
/// detect-moves = true
/// inline-diff = true
/// minimal = false
/// # match a line and its stack trace as one entry
/// multiline-entries = false
/// # off: disable every built-in rule that is not enabled explicitly below
/// builtins = on
/// # optional evaluation order; rules not listed keep their position after these
/// order = order-id, uuid
/// # auto | plain | json | logfmt
/// log-format = auto
/// # structured logs: fields left out of the comparison (replaces the default list)
/// ignore-fields = ts, traceId, spanId
/// # auto | none | spring-boot | access-log | kubernetes | journald | logcat | python
/// preset = auto
/// # compare only part of each run: +5m (into the run), 08:49:03, 2026-11-12T08:49
/// since = +1m
/// until = +5m
/// # remove secrets and e-mail addresses from exported reports
/// redact = true
///
/// [ignore]
/// # repeatable; lines matching any pattern are left out
/// pattern = ^DEBUG\b
///
/// [redact]
/// # repeatable; matches are removed from exported reports
/// pattern = customer-\d+
///
/// # Built-in rule: only 'enabled' is needed.
/// [rule iso-timestamp]
/// enabled = false
///
/// # Custom rule.
/// [rule order-id]
/// name = Order id
/// category = identifier
/// pattern = ORD-\d{6}
/// replacement = <ORDER>
/// case-insensitive = false
/// description = Order numbers differ per test run
/// ```
///
/// Comments must be on their own line, because `#` is common in regular expressions.
/// Values keep inner whitespace; wrap a value in double quotes to keep leading or trailing
/// spaces (`\"` and `\\` are escapes inside quotes). Custom rules run before the
/// built-in ones unless `order` says otherwise.
public final class ProfileIO {

    /// Conventional file extension, without the dot.
    public static final String EXTENSION = "ldprofile";

    private ProfileIO() {
    }

    /// Reads a profile file (UTF-8).
    public static DiffOptions read(Path file) throws IOException, ProfileParseException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(r);
        }
    }

    /// Parses a profile from a string.
    public static DiffOptions parse(String text) throws ProfileParseException {
        try {
            return read(new StringReader(text));
        } catch (IOException e) {
            throw new IllegalStateException("StringReader does not throw", e);
        }
    }

    /// Parses a profile.
    public static DiffOptions read(Reader reader) throws IOException, ProfileParseException {
        DiffOptions.Builder options = DiffOptions.builder();
        List<String> ignore = new ArrayList<>();
        Map<String, Map<String, String>> ruleSections = new LinkedHashMap<>();
        Map<String, Integer> ruleLines = new LinkedHashMap<>();
        Map<String, Integer> patternLines = new LinkedHashMap<>();
        boolean builtins = true;
        List<String> order = List.of();
        List<String> redact = new ArrayList<>();
        String since = null;
        String until = null;

        BufferedReader in = new BufferedReader(reader);
        String section = null;
        Map<String, String> currentRule = null;
        String currentRuleId = null;
        String raw;
        int lineNo = 0;
        while ((raw = in.readLine()) != null) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            if (line.startsWith("[")) {
                if (!line.endsWith("]")) {
                    throw new ProfileParseException(lineNo, "Unterminated section header", null);
                }
                String header = line.substring(1, line.length() - 1).strip();
                currentRule = null;
                if (header.equalsIgnoreCase("options") || header.equalsIgnoreCase("ignore")
                        || header.equalsIgnoreCase("redact")) {
                    section = header.toLowerCase(Locale.ROOT);
                } else if (header.toLowerCase(Locale.ROOT).startsWith("rule ")) {
                    String id = header.substring(5).strip();
                    if (ruleSections.containsKey(id)) {
                        throw new ProfileParseException(lineNo, "Duplicate rule section: " + id, null);
                    }
                    section = "rule";
                    currentRule = new LinkedHashMap<>();
                    currentRuleId = id;
                    ruleSections.put(id, currentRule);
                    ruleLines.put(id, lineNo);
                } else {
                    throw new ProfileParseException(lineNo, "Unknown section [" + header + "]", null);
                }
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new ProfileParseException(lineNo, "Expected 'key = value'", null);
            }
            String key = line.substring(0, eq).strip().toLowerCase(Locale.ROOT);
            String value = unquote(line.substring(eq + 1).strip(), lineNo);
            if (section == null) {
                throw new ProfileParseException(lineNo, "Setting outside of a section", null);
            }
            try {
                switch (section) {
                    case "options" -> {
                        switch (key) {
                            case "algorithm" -> options.algorithm(Algorithm.parse(value));
                            case "ignore-case" -> options.ignoreCase(bool(value, lineNo));
                            case "ignore-whitespace" -> options.ignoreWhitespace(bool(value, lineNo));
                            case "detect-moves" -> options.detectMoves(bool(value, lineNo));
                            case "inline-diff" -> options.inlineDiff(bool(value, lineNo));
                            case "minimal" -> options.minimal(bool(value, lineNo));
                            case "multiline-entries" -> options.multilineEntries(bool(value, lineNo));
                            case "builtins" -> builtins = bool(value, lineNo);
                            case "order" -> order = splitList(value);
                            case "log-format", "format" -> options.format(LogFormat.parse(value));
                            case "ignore-fields" -> options.ignoreFields(splitList(value));
                            case "preset" -> options.preset(Presets.parseOption(value));
                            case "since" -> {
                                TimeWindow.parseBound(value);
                                since = value;
                            }
                            case "until" -> {
                                TimeWindow.parseBound(value);
                                until = value;
                            }
                            case "redact" -> options.redactSecrets(bool(value, lineNo));
                            default -> throw new ProfileParseException(lineNo, "Unknown option '" + key + "'", null);
                        }
                    }
                    case "ignore" -> {
                        if (!key.equals("pattern")) {
                            throw new ProfileParseException(lineNo, "Expected 'pattern = ...' in [ignore]", null);
                        }
                        ignore.add(value);
                    }
                    case "redact" -> {
                        if (!key.equals("pattern")) {
                            throw new ProfileParseException(lineNo, "Expected 'pattern = ...' in [redact]", null);
                        }
                        redact.add(value);
                    }
                    case "rule" -> {
                        currentRule.put(key, value);
                        if (key.equals("pattern")) {
                            patternLines.put(currentRuleId, lineNo);
                        }
                    }
                    default -> throw new IllegalStateException(section);
                }
            } catch (IllegalArgumentException e) {
                throw new ProfileParseException(lineNo, e.getMessage(), e);
            }
        }

        RuleSet rules = buildRules(ruleSections, ruleLines, patternLines, builtins, order);
        try {
            if (since != null || until != null) {
                options.timeWindow(TimeWindow.parse(since, until));
            }
            return options.rules(rules).ignorePatterns(ignore).redactPatterns(redact).build();
        } catch (InvalidRuleException e) {
            throw new ProfileParseException(0, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new ProfileParseException(0, e.getMessage(), e);
        }
    }

    private static RuleSet buildRules(Map<String, Map<String, String>> sections, Map<String, Integer> lines,
                                      Map<String, Integer> patternLines, boolean builtins, List<String> order)
            throws ProfileParseException {
        List<Rule> custom = new ArrayList<>();
        Map<String, Rule> builtinOverrides = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : sections.entrySet()) {
            String id = e.getKey();
            Map<String, String> kv = e.getValue();
            int line = lines.get(id);
            Rule builtin = BuiltinRules.find(id);
            Rule presetRule = builtin == null ? Presets.findRule(id) : null;
            try {
                if (presetRule != null && !kv.containsKey("pattern")) {
                    for (String k : kv.keySet()) {
                        if (!k.equals("enabled")) {
                            throw new ProfileParseException(line, "Preset rule '" + id
                                    + "' only accepts 'enabled' unless it redefines 'pattern'", null);
                        }
                    }
                    boolean enabled = !kv.containsKey("enabled") || bool(kv.get("enabled"), line);
                    custom.add(presetRule.withEnabled(enabled));
                    continue;
                }
                if (builtin != null && !kv.containsKey("pattern")) {
                    for (String k : kv.keySet()) {
                        if (!k.equals("enabled")) {
                            throw new ProfileParseException(line, "Built-in rule '" + id
                                    + "' only accepts 'enabled' unless it redefines 'pattern'", null);
                        }
                    }
                    boolean enabled = kv.containsKey("enabled") ? bool(kv.get("enabled"), line) : true;
                    builtinOverrides.put(id, builtin.withEnabled(enabled));
                    continue;
                }
                if (!kv.containsKey("pattern")) {
                    throw new ProfileParseException(line, "Rule '" + id + "' needs a pattern", null);
                }
                Rule.Builder b = Rule.builder(id)
                        .builtin(builtin != null)
                        .name(kv.getOrDefault("name", id))
                        .description(kv.getOrDefault("description", ""))
                        .category(RuleCategory.parse(kv.get("category")))
                        .pattern(kv.get("pattern"))
                        .replacement(kv.getOrDefault("replacement", "<MASKED>"))
                        .caseInsensitive(kv.containsKey("case-insensitive") && bool(kv.get("case-insensitive"), line))
                        .enabled(!kv.containsKey("enabled") || bool(kv.get("enabled"), line));
                for (String k : kv.keySet()) {
                    if (!List.of("name", "description", "category", "pattern", "replacement", "case-insensitive",
                            "enabled").contains(k)) {
                        throw new ProfileParseException(line, "Unknown rule setting '" + k + "' in rule " + id, null);
                    }
                }
                Rule rule = b.build();
                if (builtin != null) {
                    builtinOverrides.put(id, rule);
                } else {
                    custom.add(rule);
                }
            } catch (InvalidRuleException ex) {
                throw new ProfileParseException(patternLines.getOrDefault(id, line), ex.getMessage(), ex);
            }
        }

        List<Rule> all = new ArrayList<>(custom);
        for (Rule b : BuiltinRules.all()) {
            Rule r = builtinOverrides.getOrDefault(b.id(), builtins ? b : b.withEnabled(false));
            all.add(r);
        }
        if (!order.isEmpty()) {
            List<Rule> ordered = new ArrayList<>();
            for (String id : order) {
                all.stream().filter(r -> r.id().equals(id)).findFirst().ifPresent(r -> {
                    if (!ordered.contains(r)) {
                        ordered.add(r);
                    }
                });
            }
            for (Rule r : all) {
                if (!ordered.contains(r)) {
                    ordered.add(r);
                }
            }
            all = ordered;
        }
        return RuleSet.of(all);
    }

    // ------------------------------------------------------------------------------------------

    /// Serializes options to profile text.
    public static String format(DiffOptions o) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(Version.NAME).append(" profile (").append(Version.VERSION).append(")\n");
        sb.append("# Format reference: docs/PROFILES.md\n\n");
        sb.append("[options]\n");
        sb.append("algorithm = ").append(o.algorithm().key()).append('\n');
        sb.append("ignore-case = ").append(o.ignoreCase()).append('\n');
        sb.append("ignore-whitespace = ").append(o.ignoreWhitespace()).append('\n');
        sb.append("detect-moves = ").append(o.detectMoves()).append('\n');
        sb.append("inline-diff = ").append(o.inlineDiff()).append('\n');
        sb.append("minimal = ").append(o.minimal()).append('\n');
        sb.append("multiline-entries = ").append(o.multilineEntries()).append('\n');
        sb.append("log-format = ").append(o.format().key()).append('\n');
        if (!o.ignoreFields().equals(DiffOptions.DEFAULT_IGNORED_FIELDS)) {
            sb.append("ignore-fields = ").append(String.join(", ", o.ignoreFields())).append('\n');
        }
        sb.append("preset = ").append(o.preset()).append('\n');
        if (o.timeWindow() != null) {
            if (o.timeWindow().since() != null) {
                sb.append("since = ").append(o.timeWindow().since().text()).append('\n');
            }
            if (o.timeWindow().until() != null) {
                sb.append("until = ").append(o.timeWindow().until().text()).append('\n');
            }
        }
        sb.append("redact = ").append(o.redactSecrets()).append('\n');

        // Enabled, unchanged preset rules are not saved: the preset adds them again.
        List<Rule> rules = o.rules().rules().stream().filter(r -> {
            Rule preset = Presets.findRule(r.id());
            return preset == null || !r.enabled() || !preset.equals(r);
        }).toList();
        List<String> canonical = canonicalOrder(rules);
        List<String> actual = rules.stream().map(Rule::id).toList();
        if (!canonical.equals(actual)) {
            sb.append("order = ").append(String.join(", ", actual)).append('\n');
        }

        if (!o.ignorePatterns().isEmpty()) {
            sb.append("\n[ignore]\n");
            for (String p : o.ignorePatterns()) {
                sb.append("pattern = ").append(quote(p)).append('\n');
            }
        }

        if (!o.redactPatterns().isEmpty()) {
            sb.append("\n[redact]\n");
            for (String p : o.redactPatterns()) {
                sb.append("pattern = ").append(quote(p)).append('\n');
            }
        }

        for (Rule r : rules) {
            Rule preset = Presets.findRule(r.id());
            if (preset != null && preset.withEnabled(r.enabled()).equals(r)) {
                // Preset rules come back with the preset; only a disabled one needs saving.
                if (!r.enabled()) {
                    sb.append("\n[rule ").append(r.id()).append("]\nenabled = false\n");
                }
                continue;
            }
            Rule builtin = r.builtin() ? BuiltinRules.find(r.id()) : null;
            if (builtin != null && builtin.withEnabled(r.enabled()).equals(r)) {
                if (r.enabled() != builtin.enabled()) {
                    sb.append("\n[rule ").append(r.id()).append("]\nenabled = ").append(r.enabled()).append('\n');
                }
                continue;
            }
            sb.append("\n[rule ").append(r.id()).append("]\n");
            sb.append("name = ").append(quote(r.name())).append('\n');
            sb.append("category = ").append(r.category().key()).append('\n');
            sb.append("pattern = ").append(quote(r.regex())).append('\n');
            sb.append("replacement = ").append(quote(r.replacement())).append('\n');
            if (r.caseInsensitive()) {
                sb.append("case-insensitive = true\n");
            }
            if (!r.enabled()) {
                sb.append("enabled = false\n");
            }
            if (!r.description().isEmpty()) {
                sb.append("description = ").append(quote(r.description())).append('\n');
            }
        }
        return sb.toString();
    }

    /// Writes a profile file (UTF-8).
    public static void write(DiffOptions options, Path file) throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(format(options));
        }
    }

    /// The order [#read] produces without an explicit `order`: custom rules, then built-ins.
    private static List<String> canonicalOrder(List<Rule> rules) {
        List<String> out = new ArrayList<>();
        for (Rule r : rules) {
            if (BuiltinRules.find(r.id()) == null) {
                out.add(r.id());
            }
        }
        for (Rule b : BuiltinRules.all()) {
            if (rules.stream().anyMatch(r -> r.id().equals(b.id()))) {
                out.add(b.id());
            }
        }
        return out;
    }

    private static boolean bool(String v, int line) throws ProfileParseException {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> throw new ProfileParseException(line, "Expected true/false but found '" + v + "'", null);
        };
    }

    private static List<String> splitList(String v) {
        List<String> out = new ArrayList<>();
        for (String s : v.split(",")) {
            if (!s.isBlank()) {
                out.add(s.strip());
            }
        }
        return out;
    }

    static String quote(String v) {
        boolean needs = v.isEmpty() || !v.equals(v.strip()) || v.startsWith("\"");
        if (!needs) {
            return v;
        }
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String unquote(String v, int line) throws ProfileParseException {
        if (v.length() < 2 || !v.startsWith("\"") || !v.endsWith("\"")) {
            return v;
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 1; i < v.length() - 1; i++) {
            char c = v.charAt(i);
            if (c == '\\') {
                if (i + 1 >= v.length() - 1) {
                    throw new ProfileParseException(line, "Dangling backslash in quoted value", null);
                }
                sb.append(v.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
