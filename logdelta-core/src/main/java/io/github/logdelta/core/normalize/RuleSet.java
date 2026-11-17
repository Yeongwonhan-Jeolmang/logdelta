// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.normalize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/// An immutable, ordered list of rules with unique ids.
///
/// Rules are applied in list order. All "modifying" methods return a new set.
public final class RuleSet implements Iterable<Rule> {

    private static final RuleSet DEFAULTS = new RuleSet(BuiltinRules.all());
    private static final RuleSet EMPTY = new RuleSet(List.of());

    private final List<Rule> rules;

    private RuleSet(List<Rule> rules) {
        Set<String> ids = new HashSet<>();
        for (Rule r : rules) {
            if (!ids.add(r.id())) {
                throw new InvalidRuleException("Duplicate rule id: " + r.id());
            }
        }
        this.rules = List.copyOf(rules);
    }

    /// The built-in rules with their default enabled states.
    public static RuleSet defaults() {
        return DEFAULTS;
    }

    /// A set without any rules: lines are compared verbatim.
    public static RuleSet empty() {
        return EMPTY;
    }

    /// Creates a set from an explicit list.
    public static RuleSet of(List<Rule> rules) {
        return new RuleSet(rules);
    }

    /// All rules in evaluation order.
    public List<Rule> rules() {
        return rules;
    }

    /// Only the enabled rules, in evaluation order.
    public List<Rule> enabledRules() {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules) {
            if (r.enabled()) {
                out.add(r);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /// Number of rules.
    public int size() {
        return rules.size();
    }

    /// Returns the rule with the given id, or `null`.
    public Rule find(String id) {
        for (Rule r : rules) {
            if (r.id().equals(id)) {
                return r;
            }
        }
        return null;
    }

    /// Index of the rule with the given id, or `-1`.
    public int indexOf(String id) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /// Replaces the rule with the same id, or appends `rule` if there is none.
    public RuleSet with(Rule rule) {
        List<Rule> copy = new ArrayList<>(rules);
        int i = indexOf(rule.id());
        if (i >= 0) {
            copy.set(i, rule);
        } else {
            copy.add(rule);
        }
        return new RuleSet(copy);
    }

    /// Inserts `rule` at `index`, replacing any rule with the same id.
    public RuleSet withAt(int index, Rule rule) {
        List<Rule> copy = new ArrayList<>(rules);
        copy.removeIf(r -> r.id().equals(rule.id()));
        copy.add(Math.clamp(index, 0, copy.size()), rule);
        return new RuleSet(copy);
    }

    /// Removes the rule with the given id, if present.
    public RuleSet without(String id) {
        List<Rule> copy = new ArrayList<>(rules);
        copy.removeIf(r -> r.id().equals(id));
        return new RuleSet(copy);
    }

    /// Enables or disables the rule with the given id.
    ///
    /// @throws IllegalArgumentException if no such rule exists
    public RuleSet withEnabled(String id, boolean enabled) {
        Rule r = find(id);
        if (r == null) {
            throw new IllegalArgumentException("Unknown rule: " + id);
        }
        return with(r.withEnabled(enabled));
    }

    /// Moves the rule at `from` to position `to`.
    public RuleSet move(int from, int to) {
        List<Rule> copy = new ArrayList<>(rules);
        Rule r = copy.remove(from);
        copy.add(Math.clamp(to, 0, copy.size()), r);
        return new RuleSet(copy);
    }

    /// Returns a set with every rule enabled or disabled.
    public RuleSet withAllEnabled(boolean enabled) {
        List<Rule> copy = new ArrayList<>(rules.size());
        for (Rule r : rules) {
            copy.add(r.withEnabled(enabled));
        }
        return new RuleSet(copy);
    }

    @Override
    public Iterator<Rule> iterator() {
        return rules.iterator();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RuleSet other && rules.equals(other.rules);
    }

    @Override
    public int hashCode() {
        return rules.hashCode();
    }

    @Override
    public String toString() {
        return "RuleSet" + rules;
    }
}
