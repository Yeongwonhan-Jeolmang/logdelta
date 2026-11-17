// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.args;

import java.util.Objects;

/// Declaration of one command-line option.
///
/// @param longName   name without dashes, e.g. `"format"` for `--format`
/// @param shortName  single-letter alias, or `0` for none
/// @param argName    placeholder for the value in help text, or `null` for a flag
/// @param help       one-line description
/// @param repeatable whether the option may be given more than once
public record OptionSpec(String longName, char shortName, String argName, String help, boolean repeatable) {

    /// Validates the declaration.
    public OptionSpec {
        Objects.requireNonNull(longName, "longName");
        Objects.requireNonNull(help, "help");
    }

    /// Declares a boolean flag.
    public static OptionSpec flag(String longName, char shortName, String help) {
        return new OptionSpec(longName, shortName, null, help, false);
    }

    /// Declares an option that takes a value.
    public static OptionSpec value(String longName, char shortName, String argName, String help) {
        return new OptionSpec(longName, shortName, argName, help, false);
    }

    /// Declares an option that takes a value and may be repeated.
    public static OptionSpec repeated(String longName, char shortName, String argName, String help) {
        return new OptionSpec(longName, shortName, argName, help, true);
    }

    /// Whether the option takes a value.
    public boolean takesValue() {
        return argName != null;
    }
}
