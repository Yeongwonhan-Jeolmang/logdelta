// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.args;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// A small, dependency-free command-line parser.
///
/// Supported syntax: `--name value`, `--name=value`, `-n value`,
/// `-nvalue`, grouped short flags (`-iw`) and `--` to end option parsing.
/// Options and positional arguments may be mixed in any order. Help text is generated from the
/// declarations.
public final class ArgParser {

    private final List<OptionSpec> specs = new ArrayList<>();
    private final Map<String, OptionSpec> byLong = new LinkedHashMap<>();
    private final Map<Character, OptionSpec> byShort = new LinkedHashMap<>();

    /// Declares an option.
    public ArgParser add(OptionSpec spec) {
        if (byLong.containsKey(spec.longName()) || (spec.shortName() != 0 && byShort.containsKey(spec.shortName()))) {
            throw new IllegalArgumentException("Duplicate option: " + spec.longName());
        }
        specs.add(spec);
        byLong.put(spec.longName(), spec);
        if (spec.shortName() != 0) {
            byShort.put(spec.shortName(), spec);
        }
        return this;
    }

    /// Declared options in declaration order.
    public List<OptionSpec> specs() {
        return List.copyOf(specs);
    }

    /// Parses arguments.
    ///
    /// @throws UsageException for unknown options, missing values or repeated non-repeatable options
    public ParsedArgs parse(List<String> args) throws UsageException {
        Map<String, List<String>> values = new LinkedHashMap<>();
        List<String> positionals = new ArrayList<>();
        boolean optionsEnded = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (optionsEnded || arg.equals("-") || !arg.startsWith("-")) {
                positionals.add(arg);
            } else if (arg.equals("--")) {
                optionsEnded = true;
            } else if (arg.startsWith("--")) {
                String body = arg.substring(2);
                int eq = body.indexOf('=');
                String name = eq >= 0 ? body.substring(0, eq) : body;
                OptionSpec spec = byLong.get(name);
                if (spec == null) {
                    throw new UsageException("Unknown option --" + name + suggestion(name));
                }
                String value = null;
                if (spec.takesValue()) {
                    if (eq >= 0) {
                        value = body.substring(eq + 1);
                    } else if (i + 1 < args.size()) {
                        value = args.get(++i);
                    } else {
                        throw new UsageException("--" + name + " requires a value (" + spec.argName() + ")");
                    }
                } else if (eq >= 0) {
                    throw new UsageException("--" + name + " does not take a value");
                }
                put(values, spec, value);
            } else {
                // Short option(s): -x, -xVALUE, -x VALUE, -abc (flags only)
                for (int j = 1; j < arg.length(); j++) {
                    char c = arg.charAt(j);
                    OptionSpec spec = byShort.get(c);
                    if (spec == null) {
                        throw new UsageException("Unknown option -" + c);
                    }
                    if (spec.takesValue()) {
                        String value;
                        if (j + 1 < arg.length()) {
                            value = arg.substring(j + 1);
                        } else if (i + 1 < args.size()) {
                            value = args.get(++i);
                        } else {
                            throw new UsageException("-" + c + " requires a value (" + spec.argName() + ")");
                        }
                        put(values, spec, value);
                        break;
                    }
                    put(values, spec, null);
                }
            }
        }
        return new ParsedArgs(values, positionals);
    }

    private static void put(Map<String, List<String>> values, OptionSpec spec, String value) throws UsageException {
        List<String> list = values.computeIfAbsent(spec.longName(), _ -> new ArrayList<>());
        if (!spec.repeatable() && !list.isEmpty() && spec.takesValue()) {
            throw new UsageException("--" + spec.longName() + " may only be given once");
        }
        list.add(value == null ? "" : value);
    }

    private String suggestion(String name) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : byLong.keySet()) {
            int d = distance(name, candidate);
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return best != null && bestDistance <= 2 ? " (did you mean --" + best + "?)" : "";
    }

    /// Levenshtein distance, used for "did you mean" hints.
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /// Renders the option list for help output, aligned in two columns.
    public String optionsHelp() {
        List<String[]> rows = new ArrayList<>();
        int width = 0;
        for (OptionSpec s : specs) {
            StringBuilder left = new StringBuilder("  ");
            left.append(s.shortName() != 0 ? "-" + s.shortName() + ", " : "    ");
            left.append("--").append(s.longName());
            if (s.takesValue()) {
                left.append(' ').append(s.argName());
            }
            rows.add(new String[] {left.toString(), s.help() + (s.repeatable() ? " (repeatable)" : "")});
            width = Math.max(width, left.length());
        }
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) {
            sb.append(r[0]).append(" ".repeat(width - r[0].length() + 2)).append(r[1]).append('\n');
        }
        return sb.toString();
    }
}
