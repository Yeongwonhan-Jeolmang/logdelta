// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.args;

import java.util.List;
import java.util.Map;

/// Result of parsing a command line: option values by long name, plus positional arguments.
public final class ParsedArgs {

    private final Map<String, List<String>> values;
    private final List<String> positionals;

    ParsedArgs(Map<String, List<String>> values, List<String> positionals) {
        this.values = Map.copyOf(values);
        this.positionals = List.copyOf(positionals);
    }

    /// Whether the option was given at least once.
    public boolean has(String longName) {
        return values.containsKey(longName);
    }

    /// The last value of an option, or `null` if absent.
    public String get(String longName) {
        List<String> v = values.get(longName);
        return v == null || v.isEmpty() ? null : v.getLast();
    }

    /// The last value of an option, or `fallback` if absent.
    public String get(String longName, String fallback) {
        String v = get(longName);
        return v == null ? fallback : v;
    }

    /// All values of a repeatable option, in order; empty if absent.
    public List<String> all(String longName) {
        return values.getOrDefault(longName, List.of());
    }

    /// Parses an option as a non-negative integer.
    ///
    /// @throws UsageException if the value is not a valid number
    public int getInt(String longName, int fallback) throws UsageException {
        String v = get(longName);
        if (v == null) {
            return fallback;
        }
        try {
            int n = Integer.parseInt(v.trim());
            if (n < 0) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException _) {
            throw new UsageException("--" + longName + " expects a non-negative number, got '" + v + "'");
        }
    }

    /// Positional (non-option) arguments in order.
    public List<String> positionals() {
        return positionals;
    }
}
