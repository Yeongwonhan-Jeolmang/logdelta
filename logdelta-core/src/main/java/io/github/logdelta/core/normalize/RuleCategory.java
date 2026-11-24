// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.normalize;

import java.util.Locale;

/// Groups rules in listings and in the GUI. Categories have no effect on matching.
public enum RuleCategory {
    TIMESTAMP("Timestamps"),
    IDENTIFIER("Identifiers"),
    NETWORK("Network"),
    PROCESS("Processes & threads"),
    MEMORY("Memory"),
    METRIC("Metrics"),
    PATH("Paths"),
    FORMAT("Log formats"),
    CUSTOM("Custom");

    private final String displayName;

    RuleCategory(String displayName) {
        this.displayName = displayName;
    }

    /// Human-readable name, e.g. `"Processes & threads"`.
    public String displayName() {
        return displayName;
    }

    /// Lower-case identifier used in profile files, e.g. `"timestamp"`.
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /// Returns the [display name][#displayName()], so the enum can be shown in UI lists directly.
    @Override
    public String toString() {
        return displayName;
    }

    /// Parses a [key][#key()] or display name; unknown values map to [#CUSTOM].
    public static RuleCategory parse(String s) {
        if (s == null) {
            return CUSTOM;
        }
        String t = s.trim();
        for (RuleCategory c : values()) {
            if (c.name().equalsIgnoreCase(t) || c.displayName.equalsIgnoreCase(t)) {
                return c;
            }
        }
        return CUSTOM;
    }
}
