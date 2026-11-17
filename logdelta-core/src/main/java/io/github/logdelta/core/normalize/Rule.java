// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.normalize;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/// A normalization rule: every match of [#pattern()] is replaced by [#replacement()]
/// before lines are compared.
///
/// The replacement may reference capture groups with `$1` or `${name}`; a literal
/// dollar sign or backslash is escaped with a backslash, exactly like
/// [java.util.regex.Matcher#appendReplacement].
///
/// Rules are immutable. Use [#builder(String)] to create one and [#toBuilder()] or
/// [#withEnabled(boolean)] to derive modified copies.
public final class Rule {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private final String id;
    private final String name;
    private final String description;
    private final RuleCategory category;
    private final String regex;
    private final String replacement;
    private final boolean caseInsensitive;
    private final boolean enabled;
    private final boolean builtin;
    private final Pattern pattern;

    private Rule(Builder b) {
        if (b.id == null || !ID.matcher(b.id).matches()) {
            throw new InvalidRuleException("Rule id must be lower-case letters, digits, '.', '_' or '-': " + b.id);
        }
        if (b.regex == null || b.regex.isEmpty()) {
            throw new InvalidRuleException("Rule '" + b.id + "' has no pattern");
        }
        this.id = b.id;
        this.name = b.name == null || b.name.isBlank() ? b.id : b.name.trim();
        this.description = b.description == null ? "" : b.description.trim();
        this.category = b.category == null ? RuleCategory.CUSTOM : b.category;
        this.regex = b.regex;
        this.replacement = b.replacement == null ? "" : b.replacement;
        this.caseInsensitive = b.caseInsensitive;
        this.enabled = b.enabled;
        this.builtin = b.builtin;
        try {
            int flags = caseInsensitive ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            this.pattern = Pattern.compile(regex, flags);
        } catch (PatternSyntaxException e) {
            throw new InvalidRuleException("Rule '" + id + "' has an invalid pattern: " + e.getDescription()
                    + " near index " + e.getIndex(), e);
        }
        validateReplacement();
    }

    private void validateReplacement() {
        int groups = pattern.matcher("").groupCount();
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '$') {
                if (i + 1 >= replacement.length()) {
                    throw new InvalidRuleException("Rule '" + id + "': replacement ends with '$' (use \\$ for a literal)");
                }
                char n = replacement.charAt(i + 1);
                if (Character.isDigit(n) && n - '0' > groups) {
                    throw new InvalidRuleException("Rule '" + id + "': replacement refers to group " + n
                            + " but the pattern has " + groups);
                }
            }
        }
    }

    /// Starts building a rule with the given id.
    public static Builder builder(String id) {
        return new Builder().id(id);
    }

    /// Returns a builder pre-filled with this rule's values.
    public Builder toBuilder() {
        return new Builder().id(id).name(name).description(description).category(category).pattern(regex)
                .replacement(replacement).caseInsensitive(caseInsensitive).enabled(enabled).builtin(builtin);
    }

    /// Returns a copy with a different enabled state (or this rule if unchanged).
    public Rule withEnabled(boolean value) {
        return value == enabled ? this : toBuilder().enabled(value).build();
    }

    /// Stable identifier, unique within a [RuleSet].
    public String id() {
        return id;
    }

    /// Human-readable name.
    public String name() {
        return name;
    }

    /// Longer explanation, possibly empty.
    public String description() {
        return description;
    }

    /// Category used for grouping.
    public RuleCategory category() {
        return category;
    }

    /// The regular expression source.
    public String regex() {
        return regex;
    }

    /// Replacement text, possibly containing group references.
    public String replacement() {
        return replacement;
    }

    /// Whether the pattern ignores case.
    public boolean caseInsensitive() {
        return caseInsensitive;
    }

    /// Whether the rule takes part in normalization.
    public boolean enabled() {
        return enabled;
    }

    /// Whether the rule ships with LogDelta.
    public boolean builtin() {
        return builtin;
    }

    /// The compiled pattern.
    public Pattern pattern() {
        return pattern;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Rule r)) {
            return false;
        }
        return caseInsensitive == r.caseInsensitive && enabled == r.enabled && builtin == r.builtin
                && id.equals(r.id) && name.equals(r.name) && description.equals(r.description)
                && category == r.category && regex.equals(r.regex) && replacement.equals(r.replacement);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, description, category, regex, replacement, caseInsensitive, enabled, builtin);
    }

    @Override
    public String toString() {
        return "Rule[" + id + (enabled ? "" : ", disabled") + ": /" + regex + "/ -> " + replacement + "]";
    }

    /// Mutable builder for [Rule].
    public static final class Builder {
        private String id;
        private String name;
        private String description;
        private RuleCategory category = RuleCategory.CUSTOM;
        private String regex;
        private String replacement = "";
        private boolean caseInsensitive;
        private boolean enabled = true;
        private boolean builtin;

        private Builder() {
        }

        public Builder id(String v) {
            this.id = v;
            return this;
        }

        public Builder name(String v) {
            this.name = v;
            return this;
        }

        public Builder description(String v) {
            this.description = v;
            return this;
        }

        public Builder category(RuleCategory v) {
            this.category = v;
            return this;
        }

        /// Sets the regular expression source.
        public Builder pattern(String v) {
            this.regex = v;
            return this;
        }

        public Builder replacement(String v) {
            this.replacement = v;
            return this;
        }

        public Builder caseInsensitive(boolean v) {
            this.caseInsensitive = v;
            return this;
        }

        public Builder enabled(boolean v) {
            this.enabled = v;
            return this;
        }

        public Builder builtin(boolean v) {
            this.builtin = v;
            return this;
        }

        /// Builds the rule.
        ///
        /// @throws InvalidRuleException if the id or pattern is invalid
        public Rule build() {
            return new Rule(this);
        }
    }
}
