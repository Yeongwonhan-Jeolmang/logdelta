// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.normalize;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;

/// Applies a [RuleSet] to lines and derives the keys used for comparison.
///
/// ## How rules are applied
///
/// The line is kept as a list of segments that are either untouched original text or a mask.
/// Each enabled rule, in order, searches only the untouched segments, so a rule never matches
/// inside text that an earlier rule replaced. Matching uses
/// [transparent bounds][Matcher#useTransparentBounds(boolean)]: word boundaries and
/// look-arounds see the neighbouring original characters. Because every mask remembers its
/// offsets in the original line, front-ends can show exactly which characters were ignored.
///
/// ## Comparison key
///
/// [#key(NormalizedText)] additionally applies the whitespace and case options. Two lines
/// are considered equal when their keys are equal.
///
/// Instances are immutable and thread-safe.
public final class Normalizer {

    private final List<Rule> rules;
    private final boolean ignoreCase;
    private final boolean ignoreWhitespace;
    /// One reusable matcher per rule and thread; creating matchers dominated the cost on short lines.
    private final ThreadLocal<Matcher[]> matchers;

    /// @param ruleSet          rules to apply (disabled rules are skipped)
    /// @param ignoreCase       compare case-insensitively
    /// @param ignoreWhitespace treat runs of whitespace as one space and ignore leading/trailing whitespace
    public Normalizer(RuleSet ruleSet, boolean ignoreCase, boolean ignoreWhitespace) {
        this.rules = ruleSet.enabledRules();
        this.ignoreCase = ignoreCase;
        this.ignoreWhitespace = ignoreWhitespace;
        this.matchers = ThreadLocal.withInitial(() -> {
            Matcher[] ms = new Matcher[rules.size()];
            for (int i = 0; i < ms.length; i++) {
                ms[i] = rules.get(i).pattern().matcher("");
                ms[i].useTransparentBounds(true);
                ms[i].useAnchoringBounds(false);
            }
            return ms;
        });
    }

    /// A normalizer that uses the default rules and exact whitespace/case comparison.
    public static Normalizer defaults() {
        return new Normalizer(RuleSet.defaults(), false, false);
    }

    /// Normalizes a line, recording which regions each rule replaced.
    public NormalizedText normalize(String line) {
        if (rules.isEmpty() || line.isEmpty()) {
            return new NormalizedText(line, line, List.of());
        }
        // Segments: literal ranges have replacement == null.
        List<Segment> segments = new ArrayList<>();
        segments.add(new Segment(0, line.length(), null, null));
        Matcher[] ms = matchers.get();
        for (int r = 0; r < ms.length; r++) {
            Rule rule = rules.get(r);
            Matcher m = ms[r].reset(line);
            List<Segment> next = null;
            for (int i = 0; i < segments.size(); i++) {
                Segment seg = segments.get(i);
                if (seg.replacement != null) {
                    if (next != null) {
                        next.add(seg);
                    }
                    continue;
                }
                m.region(seg.start, seg.end);
                int pos = seg.start;
                while (m.find()) {
                    if (m.start() == m.end()) {
                        continue; // empty matches would create zero-length masks
                    }
                    if (next == null) {
                        next = new ArrayList<>(segments.size() + 4);
                        next.addAll(segments.subList(0, i));
                    }
                    if (m.start() > pos) {
                        next.add(new Segment(pos, m.start(), null, null));
                    }
                    next.add(new Segment(m.start(), m.end(), expand(m, rule.replacement()), rule.id()));
                    pos = m.end();
                }
                if (next != null && pos < seg.end) {
                    next.add(new Segment(pos, seg.end, null, null));
                }
            }
            if (next != null) {
                segments = next;
            }
        }

        StringBuilder sb = new StringBuilder(line.length());
        List<Mask> masks = new ArrayList<>();
        for (Segment s : segments) {
            if (s.replacement == null) {
                sb.append(line, s.start, s.end);
            } else {
                sb.append(s.replacement);
                masks.add(new Mask(s.start, s.end, s.replacement, s.ruleId));
            }
        }
        return masks.isEmpty() ? new NormalizedText(line, line, List.of())
                : new NormalizedText(line, sb.toString(), masks);
    }

    /// Returns the comparison key for an already normalized line.
    public String key(NormalizedText text) {
        return applyOptions(text.normalized());
    }

    /// Normalizes `line` and returns its comparison key.
    public String key(String line) {
        return key(normalize(line));
    }

    /// Applies only the case and whitespace options to `s`.
    public String applyOptions(String s) {
        String out = s;
        if (ignoreWhitespace) {
            out = collapseWhitespace(out);
        }
        if (ignoreCase) {
            out = out.toLowerCase(Locale.ROOT);
        }
        return out;
    }

    /// Whether keys are case-insensitive.
    public boolean ignoreCase() {
        return ignoreCase;
    }

    /// Whether keys ignore whitespace differences.
    public boolean ignoreWhitespace() {
        return ignoreWhitespace;
    }

    static String collapseWhitespace(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean pendingSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = sb.length() > 0;
            } else {
                if (pendingSpace) {
                    sb.append(' ');
                    pendingSpace = false;
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /// Expands `$n`, `${name}` and backslash escapes in a replacement for the current
    /// match, mirroring [Matcher#appendReplacement].
    static String expand(Matcher m, String replacement) {
        if (replacement.indexOf('$') < 0 && replacement.indexOf('\\') < 0) {
            return replacement;
        }
        StringBuilder sb = new StringBuilder(replacement.length() + 16);
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c == '\\' && i + 1 < replacement.length()) {
                sb.append(replacement.charAt(++i));
            } else if (c == '$' && i + 1 < replacement.length()) {
                char n = replacement.charAt(i + 1);
                if (n == '{') {
                    int close = replacement.indexOf('}', i + 2);
                    if (close < 0) {
                        sb.append(c);
                        continue;
                    }
                    String g = m.group(replacement.substring(i + 2, close));
                    sb.append(g == null ? "" : g);
                    i = close;
                } else if (Character.isDigit(n)) {
                    int group = n - '0';
                    i++;
                    // Greedily consume more digits while they still name an existing group.
                    while (i + 1 < replacement.length() && Character.isDigit(replacement.charAt(i + 1))) {
                        int candidate = group * 10 + (replacement.charAt(i + 1) - '0');
                        if (candidate > m.groupCount()) {
                            break;
                        }
                        group = candidate;
                        i++;
                    }
                    String g = group <= m.groupCount() ? m.group(group) : null;
                    sb.append(g == null ? "" : g);
                } else {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private record Segment(int start, int end, String replacement, String ruleId) {
    }
}
