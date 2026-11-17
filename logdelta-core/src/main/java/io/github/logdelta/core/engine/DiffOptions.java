// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.engine;

import io.github.logdelta.core.diff.Algorithm;
import io.github.logdelta.core.normalize.LineFilter;
import io.github.logdelta.core.normalize.Normalizer;
import io.github.logdelta.core.normalize.RuleSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Everything that controls a comparison. Immutable; create instances with [#builder()].
///
/// Defaults: built-in rules, exact case and whitespace, Myers with heuristic, move detection
/// and inline highlighting on, no ignore patterns.
public final class DiffOptions {

    private static final DiffOptions DEFAULTS = builder().build();

    private final RuleSet rules;
    private final List<String> ignorePatterns;
    private final boolean ignoreCase;
    private final boolean ignoreWhitespace;
    private final Algorithm algorithm;
    private final boolean minimal;
    private final boolean detectMoves;
    private final boolean inlineDiff;
    private final boolean multilineEntries;

    private DiffOptions(Builder b) {
        this.rules = Objects.requireNonNull(b.rules, "rules");
        this.ignorePatterns = List.copyOf(b.ignorePatterns);
        this.ignoreCase = b.ignoreCase;
        this.ignoreWhitespace = b.ignoreWhitespace;
        this.algorithm = Objects.requireNonNull(b.algorithm, "algorithm");
        this.minimal = b.minimal;
        this.detectMoves = b.detectMoves;
        this.inlineDiff = b.inlineDiff;
        this.multilineEntries = b.multilineEntries;
        LineFilter.of(ignorePatterns); // fail fast on invalid patterns
    }

    /// The default options.
    public static DiffOptions defaults() {
        return DEFAULTS;
    }

    /// A builder initialised with the defaults.
    public static Builder builder() {
        return new Builder();
    }

    /// A builder initialised with this instance's values.
    public Builder toBuilder() {
        Builder b = new Builder();
        b.rules = rules;
        b.ignorePatterns = new ArrayList<>(ignorePatterns);
        b.ignoreCase = ignoreCase;
        b.ignoreWhitespace = ignoreWhitespace;
        b.algorithm = algorithm;
        b.minimal = minimal;
        b.detectMoves = detectMoves;
        b.inlineDiff = inlineDiff;
        b.multilineEntries = multilineEntries;
        return b;
    }

    /// Creates the normalizer described by these options.
    public Normalizer normalizer() {
        return new Normalizer(rules, ignoreCase, ignoreWhitespace);
    }

    /// Creates the line filter described by these options.
    public LineFilter lineFilter() {
        return LineFilter.of(ignorePatterns);
    }

    public RuleSet rules() {
        return rules;
    }

    /// Regular expressions; lines matching any of them are left out of the comparison.
    public List<String> ignorePatterns() {
        return ignorePatterns;
    }

    public boolean ignoreCase() {
        return ignoreCase;
    }

    public boolean ignoreWhitespace() {
        return ignoreWhitespace;
    }

    public Algorithm algorithm() {
        return algorithm;
    }

    /// Whether the diff must be minimal even when that is expensive.
    public boolean minimal() {
        return minimal;
    }

    /// Whether removed/added pairs of identical lines are reported as moves.
    public boolean detectMoves() {
        return detectMoves;
    }

    /// Whether word-level highlights are computed for modified rows.
    public boolean inlineDiff() {
        return inlineDiff;
    }

    /// Whether a line and its continuation lines (stack frames, wrapped text) are matched as one
    /// entry, so that a whole exception moves, matches or differs together.
    public boolean multilineEntries() {
        return multilineEntries;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DiffOptions d && rules.equals(d.rules) && ignorePatterns.equals(d.ignorePatterns)
                && ignoreCase == d.ignoreCase && ignoreWhitespace == d.ignoreWhitespace && algorithm == d.algorithm
                && minimal == d.minimal && detectMoves == d.detectMoves && inlineDiff == d.inlineDiff
                && multilineEntries == d.multilineEntries;
    }

    @Override
    public int hashCode() {
        return Objects.hash(rules, ignorePatterns, ignoreCase, ignoreWhitespace, algorithm, minimal, detectMoves,
                inlineDiff, multilineEntries);
    }

    /// Mutable builder for [DiffOptions].
    public static final class Builder {
        private RuleSet rules = RuleSet.defaults();
        private List<String> ignorePatterns = new ArrayList<>();
        private boolean ignoreCase;
        private boolean ignoreWhitespace;
        private Algorithm algorithm = Algorithm.MYERS;
        private boolean minimal;
        private boolean detectMoves = true;
        private boolean inlineDiff = true;
        private boolean multilineEntries;

        private Builder() {
        }

        public Builder rules(RuleSet v) {
            this.rules = v;
            return this;
        }

        public Builder ignorePatterns(List<String> v) {
            this.ignorePatterns = new ArrayList<>(v);
            return this;
        }

        public Builder addIgnorePattern(String regex) {
            this.ignorePatterns.add(regex);
            return this;
        }

        public Builder ignoreCase(boolean v) {
            this.ignoreCase = v;
            return this;
        }

        public Builder ignoreWhitespace(boolean v) {
            this.ignoreWhitespace = v;
            return this;
        }

        public Builder algorithm(Algorithm v) {
            this.algorithm = v;
            return this;
        }

        public Builder minimal(boolean v) {
            this.minimal = v;
            return this;
        }

        public Builder detectMoves(boolean v) {
            this.detectMoves = v;
            return this;
        }

        public Builder inlineDiff(boolean v) {
            this.inlineDiff = v;
            return this;
        }

        public Builder multilineEntries(boolean v) {
            this.multilineEntries = v;
            return this;
        }

        /// Builds the options.
        ///
        /// @throws io.github.logdelta.core.normalize.InvalidRuleException if an ignore pattern is invalid
        public DiffOptions build() {
            return new DiffOptions(this);
        }
    }
}
