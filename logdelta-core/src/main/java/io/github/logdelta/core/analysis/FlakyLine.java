// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.analysis;

import io.github.logdelta.core.normalize.Rule;

import java.util.List;

/// A baseline line that is not stable between good runs: it changes on every run, or appears in
/// only some of them. Found by comparing the baselines with each other when there are several.
///
/// Flaky lines are noise: each one is offered with a fix, either [#rules()] that mask the parts
/// that vary or an [#ignorePattern()] that leaves the line out. Lines that would get the same fix
/// (such as many `Charged ORD-… amount=…` lines) are reported once, with [#similarLines()].
///
/// @param kind          how the line varies
/// @param lineNumber    line number in the main baseline (or in the first baseline that has it)
/// @param text          the line as it appears there
/// @param varyingRuns   number of other baselines in which the line differs or is missing
/// @param totalRuns     number of baselines compared (including the main one)
/// @param rules         rules that together mask the varying parts (empty if none was found)
/// @param ignorePattern a regular expression that matches the line, for an ignore pattern, or `null`
/// @param example       how the line looks in another run (for [Kind#VALUES]), or `null`
/// @param similarLines  further baseline lines with the same fix
public record FlakyLine(Kind kind, int lineNumber, String text, int varyingRuns, int totalRuns, List<Rule> rules,
                        String ignorePattern, String example, int similarLines) {

    /// Copies the rule list.
    public FlakyLine {
        rules = List.copyOf(rules);
    }

    /// The first rule of the fix, or `null` when the fix is an ignore pattern.
    public Rule rule() {
        return rules.isEmpty() ? null : rules.getFirst();
    }

    /// Returns a copy with a different count of similar lines.
    public FlakyLine withSimilarLines(int n) {
        return new FlakyLine(kind, lineNumber, text, varyingRuns, totalRuns, rules, ignorePattern, example, n);
    }

    /// How a flaky line varies.
    public enum Kind {
        /// The line is present in every run but some values in it differ.
        VALUES("changes between runs"),
        /// The line is present in some runs and missing from others.
        PRESENCE("appears in some runs only");

        private final String description;

        Kind(String description) {
            this.description = description;
        }

        /// Short phrase for display, e.g. "changes between runs".
        public String description() {
            return description;
        }
    }
}
