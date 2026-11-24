// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.engine;

import io.github.logdelta.core.diff.Algorithm;
import io.github.logdelta.core.normalize.LineFilter;
import io.github.logdelta.core.normalize.Normalizer;
import io.github.logdelta.core.normalize.RuleSet;
import io.github.logdelta.core.preset.Presets;
import io.github.logdelta.core.redact.Redactor;
import io.github.logdelta.core.structured.FieldFilter;
import io.github.logdelta.core.structured.LogFormat;
import io.github.logdelta.core.timing.TimeWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Everything that controls a comparison. Immutable; create instances with [#builder()].
///
/// Defaults: built-in rules, exact case and whitespace, Myers with heuristic, move detection
/// and inline highlighting on, no ignore patterns, automatic log format and preset detection,
/// [default ignored fields][#DEFAULT_IGNORED_FIELDS], no time window, and secrets redacted
/// in exports.
public final class DiffOptions {

    /// Fields of structured lines that are ignored unless configured otherwise: timestamps and
    /// trace/span ids, which differ on every run.
    public static final List<String> DEFAULT_IGNORED_FIELDS = List.of("@timestamp", "timestamp", "time", "ts",
            "datetime", "traceId", "trace_id", "trace.id", "spanId", "span_id", "span.id", "parentSpanId",
            "parent_span_id");

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
    private final LogFormat format;
    private final List<String> ignoreFields;
    private final String preset;
    private final TimeWindow timeWindow;
    private final boolean redactSecrets;
    private final List<String> redactPatterns;

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
        this.format = Objects.requireNonNull(b.format, "format");
        this.ignoreFields = List.copyOf(b.ignoreFields);
        this.preset = Presets.parseOption(b.preset);
        this.timeWindow = b.timeWindow;
        this.redactSecrets = b.redactSecrets;
        this.redactPatterns = List.copyOf(b.redactPatterns);
        LineFilter.of(ignorePatterns); // fail fast on invalid patterns
        redactor(); // and on invalid redaction patterns
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
        b.format = format;
        b.ignoreFields = new ArrayList<>(ignoreFields);
        b.preset = preset;
        b.timeWindow = timeWindow;
        b.redactSecrets = redactSecrets;
        b.redactPatterns = new ArrayList<>(redactPatterns);
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

    /// Creates the filter for ignored fields of structured lines.
    public FieldFilter fieldFilter() {
        return FieldFilter.of(ignoreFields);
    }

    /// Creates the redactor for exports described by these options.
    public Redactor redactor() {
        Redactor base = redactSecrets ? Redactor.defaults() : Redactor.none();
        return base.withPatterns(redactPatterns);
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

    /// How lines are interpreted: plain text, JSON or logfmt, or detected automatically.
    public LogFormat format() {
        return format;
    }

    /// Names (or globs) of structured fields left out of the comparison; see
    /// [io.github.logdelta.core.structured.FieldFilter].
    public List<String> ignoreFields() {
        return ignoreFields;
    }

    /// Built-in profile for a known log format: [Presets#AUTO], [Presets#NONE] or a preset id.
    public String preset() {
        return preset;
    }

    /// The stretch of time to compare, or `null` for whole logs.
    public TimeWindow timeWindow() {
        return timeWindow;
    }

    /// Whether the built-in secret detectors run on exported reports.
    public boolean redactSecrets() {
        return redactSecrets;
    }

    /// Additional regular expressions whose matches are redacted from exports.
    public List<String> redactPatterns() {
        return redactPatterns;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DiffOptions d && rules.equals(d.rules) && ignorePatterns.equals(d.ignorePatterns)
                && ignoreCase == d.ignoreCase && ignoreWhitespace == d.ignoreWhitespace && algorithm == d.algorithm
                && minimal == d.minimal && detectMoves == d.detectMoves && inlineDiff == d.inlineDiff
                && multilineEntries == d.multilineEntries && format == d.format
                && ignoreFields.equals(d.ignoreFields) && preset.equals(d.preset)
                && Objects.equals(timeWindow, d.timeWindow) && redactSecrets == d.redactSecrets
                && redactPatterns.equals(d.redactPatterns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rules, ignorePatterns, ignoreCase, ignoreWhitespace, algorithm, minimal, detectMoves,
                inlineDiff, multilineEntries, format, ignoreFields, preset, timeWindow, redactSecrets,
                redactPatterns);
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
        private LogFormat format = LogFormat.AUTO;
        private List<String> ignoreFields = new ArrayList<>(DEFAULT_IGNORED_FIELDS);
        private String preset = Presets.AUTO;
        private TimeWindow timeWindow;
        private boolean redactSecrets = true;
        private List<String> redactPatterns = new ArrayList<>();

        private Builder() {
        }

        public Builder format(LogFormat v) {
            this.format = v;
            return this;
        }

        /// Replaces the ignored fields.
        public Builder ignoreFields(List<String> v) {
            this.ignoreFields = new ArrayList<>(v);
            return this;
        }

        /// Adds an ignored field (if not already present).
        public Builder addIgnoreField(String name) {
            if (!this.ignoreFields.contains(name)) {
                this.ignoreFields.add(name);
            }
            return this;
        }

        /// Sets the preset: `auto`, `none` or a preset id.
        public Builder preset(String v) {
            this.preset = v;
            return this;
        }

        public Builder timeWindow(TimeWindow v) {
            this.timeWindow = v;
            return this;
        }

        public Builder redactSecrets(boolean v) {
            this.redactSecrets = v;
            return this;
        }

        public Builder redactPatterns(List<String> v) {
            this.redactPatterns = new ArrayList<>(v);
            return this;
        }

        public Builder addRedactPattern(String regex) {
            this.redactPatterns.add(regex);
            return this;
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
        /// @throws io.github.logdelta.core.normalize.InvalidRuleException if an ignore or redaction
        ///         pattern is invalid
        /// @throws IllegalArgumentException if the preset is unknown
        public DiffOptions build() {
            return new DiffOptions(this);
        }
    }
}
