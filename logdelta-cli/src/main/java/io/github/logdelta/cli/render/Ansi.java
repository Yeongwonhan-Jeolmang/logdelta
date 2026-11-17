// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.render;

/// ANSI escape sequences, applied only when colour output is enabled.
///
/// Only the 16 basic colours are used so that output respects the user's terminal theme.
public final class Ansi {

    private static final String RESET = "\u001b[0m";

    private final boolean enabled;

    private Ansi(boolean enabled) {
        this.enabled = enabled;
    }

    /// Creates a styler; with `enabled == false` every method returns its input.
    public static Ansi of(boolean enabled) {
        return new Ansi(enabled);
    }

    /// Whether escape sequences are emitted.
    public boolean enabled() {
        return enabled;
    }

    public String red(String s) {
        return wrap("31", s);
    }

    public String green(String s) {
        return wrap("32", s);
    }

    public String yellow(String s) {
        return wrap("33", s);
    }

    public String blue(String s) {
        return wrap("34", s);
    }

    public String magenta(String s) {
        return wrap("35", s);
    }

    public String cyan(String s) {
        return wrap("36", s);
    }

    public String bold(String s) {
        return wrap("1", s);
    }

    public String dim(String s) {
        return wrap("2", s);
    }

    /// Applies raw SGR parameters, e.g. `"1;31"`.
    public String wrap(String sgr, String s) {
        if (!enabled || s.isEmpty()) {
            return s;
        }
        return "\u001b[" + sgr + "m" + s + RESET;
    }

    /// Starts a style without resetting; pair with [#reset()].
    public String start(String sgr) {
        return enabled ? "\u001b[" + sgr + "m" : "";
    }

    /// Resets all styles.
    public String reset() {
        return enabled ? RESET : "";
    }
}
