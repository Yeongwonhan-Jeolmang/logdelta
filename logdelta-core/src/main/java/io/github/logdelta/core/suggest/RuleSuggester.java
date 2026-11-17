// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.suggest;

import io.github.logdelta.core.diff.InlineDiff;
import io.github.logdelta.core.diff.InlineHighlights;
import io.github.logdelta.core.diff.Span;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.ProcessedLine;
import io.github.logdelta.core.model.RowKind;
import io.github.logdelta.core.normalize.InvalidRuleException;
import io.github.logdelta.core.normalize.Normalizer;
import io.github.logdelta.core.normalize.Rule;
import io.github.logdelta.core.normalize.RuleCategory;
import io.github.logdelta.core.normalize.RuleSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Proposes rules for differences that look like run-specific values.
///
/// For every modified line, the changed regions are widened to whole values and compared. A
/// candidate rule is derived from each changed value, preferring, in order:
///
/// 1. a `key=value` or `key: value` context: mask the value, keep the key;
/// 2. a number right after a word (`attempt 3`): mask the number, keep the word;
/// 3. the **shape** shared by both values (`ORD-123456` / `ORD-654321` gives `ORD-\d{6}`),
///    as long as the shape is specific enough not to swallow ordinary numbers.
///
/// Run-specific values (ids, session tokens, timings) are different on almost every line,
/// whereas real changes (`status=OK` becoming `status=FAILED`, HTTP 201 becoming 502) repeat
/// the same few values. A candidate is therefore dropped unless at least half of the value
/// pairs it would hide are distinct.
///
/// Every remaining candidate is then **validated**: the affected lines are normalized again
/// with the candidate added in front of the existing rules, and only candidates that make at
/// least [#MIN_RESOLVES] modified lines identical are proposed.
public final class RuleSuggester {

    /// Minimum number of lines a suggestion must make identical.
    public static final int MIN_RESOLVES = 2;
    /// Maximum number of suggestions returned.
    public static final int MAX_SUGGESTIONS = 5;

    private static final int MAX_ROWS = 5000;
    private static final int MAX_CANDIDATES = 15;
    private static final Pattern KEY_CONTEXT = Pattern.compile("([A-Za-z_][\\w.-]{0,40})\\s*[=:]\\s*[\"']?$");
    private static final Pattern WORD_CONTEXT = Pattern.compile("\\b([A-Za-z]{2,30})\\s+$");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]+");

    private RuleSuggester() {
    }

    /// Returns up to [#MAX_SUGGESTIONS] validated suggestions, most effective first.
    public static List<RuleSuggestion> suggest(List<DiffRow> rows, List<ProcessedLine> left, List<ProcessedLine> right,
                                               RuleSet rules, boolean ignoreCase, boolean ignoreWhitespace) {
        List<Integer> modified = new ArrayList<>();
        for (int i = 0; i < rows.size() && modified.size() < MAX_ROWS; i++) {
            if (rows.get(i).kind() == RowKind.MODIFIED) {
                modified.add(i);
            }
        }
        if (modified.size() < MIN_RESOLVES) {
            return List.of();
        }
        // regex -> candidate (counted once per row)
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (int i : modified) {
            DiffRow row = rows.get(i);
            String l = left.get(row.left()).normalized();
            String r = right.get(row.right()).normalized();
            Set<String> seen = new HashSet<>();
            for (Candidate c : candidatesFor(l, r)) {
                if (seen.add(c.regex)) {
                    Candidate existing = candidates.computeIfAbsent(c.regex, _ -> c);
                    existing.rows++;
                    existing.distinctValues.add(c.valuePair);
                    if (existing.exampleRow < 0) {
                        existing.exampleRow = i;
                    }
                }
            }
        }
        List<Candidate> ranked = new ArrayList<>(candidates.values());
        ranked.removeIf(c -> c.rows < MIN_RESOLVES || c.distinctValues.size() * 2 < c.rows);
        ranked.sort(Comparator.comparingInt((Candidate c) -> -c.rows).thenComparing(c -> c.regex));
        if (ranked.size() > MAX_CANDIDATES) {
            ranked = ranked.subList(0, MAX_CANDIDATES);
        }

        Set<String> existingPatterns = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (Rule r : rules) {
            existingPatterns.add(r.regex());
            ids.add(r.id());
        }
        List<RuleSuggestion> out = new ArrayList<>();
        for (Candidate c : ranked) {
            if (existingPatterns.contains(c.regex)) {
                continue;
            }
            Rule rule;
            try {
                rule = Rule.builder(uniqueId(c.idBase, ids)).name(c.name).category(RuleCategory.CUSTOM)
                        .pattern(c.regex).replacement(c.replacement).description("Suggested: " + c.summary).build();
            } catch (InvalidRuleException e) {
                continue;
            }
            Normalizer n = new Normalizer(rules.withAt(0, rule), ignoreCase, ignoreWhitespace);
            int resolves = 0;
            int example = -1;
            for (int i : modified) {
                DiffRow row = rows.get(i);
                if (n.key(left.get(row.left()).text()).equals(n.key(right.get(row.right()).text()))) {
                    resolves++;
                    if (example < 0) {
                        example = i;
                    }
                }
            }
            if (resolves >= MIN_RESOLVES) {
                ids.add(rule.id());
                DiffRow ex = rows.get(example);
                out.add(new RuleSuggestion(rule, c.summary, resolves, example, left.get(ex.left()).text(),
                        right.get(ex.right()).text()));
            }
        }
        out.sort(Comparator.comparingInt(RuleSuggestion::resolves).reversed());
        return out.size() > MAX_SUGGESTIONS ? List.copyOf(out.subList(0, MAX_SUGGESTIONS)) : List.copyOf(out);
    }

    /// Candidates for one modified line; package-private for tests.
    static List<Candidate> candidatesFor(String l, String r) {
        InlineHighlights h = InlineDiff.compute(l, r);
        List<Candidate> out = new ArrayList<>();
        if (h.left().size() != h.right().size() || h.left().isEmpty()) {
            return out;
        }
        for (int k = 0; k < h.left().size(); k++) {
            Span sl = widen(l, h.left().get(k));
            Span sr = widen(r, h.right().get(k));
            String vl = l.substring(sl.start(), sl.end());
            String vr = r.substring(sr.start(), sr.end());
            if (vl.isEmpty() || vr.isEmpty() || vl.equals(vr) || vl.contains("<") || vr.contains("<")) {
                continue;
            }
            String prefix = l.substring(0, sl.start());
            Candidate c = fromContext(prefix, vl, vr);
            if (c == null) {
                c = fromShape(vl, vr);
            }
            if (c != null) {
                c.valuePair = vl + '\u0000' + vr;
                out.add(c);
            }
        }
        return out;
    }

    private static Candidate fromContext(String prefix, String vl, String vr) {
        Matcher key = KEY_CONTEXT.matcher(prefix);
        if (key.find()) {
            String k = key.group(1);
            String regex = "(\\b" + escape(k) + "\\s*[=:]\\s*[\"']?)[^\\s,;\"')\\]}]+";
            return new Candidate(regex, "$1<" + placeholder(k) + ">", "mask-" + slug(k),
                    "Value of " + k, "differ only in the value of `" + k + "`");
        }
        Matcher word = WORD_CONTEXT.matcher(prefix);
        if (word.find() && NUMBER.matcher(vl).matches() && NUMBER.matcher(vr).matches()) {
            String w = word.group(1);
            String regex = "(\\b" + escape(w) + "\\s+)\\d+(?:\\.\\d+)?\\b";
            return new Candidate(regex, "$1<N>", "number-after-" + slug(w), "Number after \"" + w + "\"",
                    "differ only in the number after `" + w + "`");
        }
        return null;
    }

    /// Builds a pattern from the common shape of two values, or `null` if there is none
    /// specific enough. Package-private for tests.
    static Candidate fromShape(String a, String b) {
        List<String[]> ra = runs(a);
        List<String[]> rb = runs(b);
        StringBuilder regex = new StringBuilder();
        StringBuilder replacement = new StringBuilder();
        boolean anchored = false;
        int varying = 0;
        if (ra.size() == rb.size() && sameTypes(ra, rb)) {
            for (int i = 0; i < ra.size(); i++) {
                String type = ra.get(i)[0];
                String x = ra.get(i)[1];
                String y = rb.get(i)[1];
                if (x.equals(y)) {
                    regex.append(escape(x));
                    replacement.append(x.replace("\\", "\\\\").replace("$", "\\$"));
                    anchored |= type.equals("L") && x.length() >= 2;
                } else if (type.equals("D")) {
                    regex.append(x.length() == y.length() ? "\\d{" + x.length() + "}" : "\\d+");
                    replacement.append("<N>");
                    varying += Math.max(x.length(), y.length());
                } else if (type.equals("L")) {
                    regex.append("[A-Za-z]+");
                    replacement.append("<ID>");
                    varying += Math.max(x.length(), y.length());
                } else {
                    return null;
                }
            }
        } else if (a.length() == b.length() && a.length() >= 8 && HEX.matcher(a).matches() && HEX.matcher(b).matches()) {
            regex.append("[0-9a-fA-F]{").append(a.length()).append('}');
            replacement.append("<HEX>");
            varying = a.length();
        } else {
            return null;
        }
        if (varying == 0 || (!anchored && varying < 6)) {
            return null; // would mask ordinary short numbers or words everywhere
        }
        String core = regex.toString();
        String full = (Character.isLetterOrDigit(a.charAt(0)) ? "\\b" : "") + core
                + (Character.isLetterOrDigit(a.charAt(a.length() - 1)) ? "\\b" : "");
        String example = a.length() > 30 ? a.substring(0, 30) + "…" : a;
        return new Candidate(full, replacement.toString(), "values-like-" + slug(example),
                "Values like " + example, "differ only in values like `" + example + "`");
    }

    /// Splits a value into runs of digits (`D`), letters (`L`) and single other characters (`O`).
    private static List<String[]> runs(String s) {
        List<String[]> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int j = i + 1;
            String type;
            if (Character.isDigit(c)) {
                type = "D";
                while (j < s.length() && Character.isDigit(s.charAt(j))) {
                    j++;
                }
            } else if (Character.isLetter(c)) {
                type = "L";
                while (j < s.length() && Character.isLetter(s.charAt(j))) {
                    j++;
                }
            } else {
                type = "O";
            }
            out.add(new String[] {type, s.substring(i, j)});
            i = j;
        }
        return out;
    }

    private static boolean sameTypes(List<String[]> a, List<String[]> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i)[0].equals(b.get(i)[0])) {
                return false;
            }
        }
        return true;
    }

    /// Widens a changed span to the whole value it belongs to.
    private static Span widen(String s, Span span) {
        int start = span.start();
        int end = span.end();
        while (start > 0 && isValueChar(s.charAt(start - 1))) {
            start--;
        }
        while (end < s.length() && isValueChar(s.charAt(end))) {
            end++;
        }
        return new Span(start, end);
    }

    private static boolean isValueChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '/' || c == '+' || c == '#';
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String placeholder(String key) {
        String p = key.replaceAll("[^A-Za-z0-9]+", "_").toUpperCase(Locale.ROOT);
        return p.isEmpty() ? "VALUE" : p;
    }

    private static String slug(String s) {
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (t.isEmpty() || !Character.isLetterOrDigit(t.charAt(0))) {
            t = "x" + t;
        }
        return t.length() > 30 ? t.substring(0, 30).replaceAll("-$", "") : t;
    }

    private static String uniqueId(String base, Set<String> taken) {
        if (!taken.contains(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            if (!taken.contains(base + "-" + n)) {
                return base + "-" + n;
            }
        }
    }

    /// A proposed rule before validation.
    static final class Candidate {
        final String regex;
        final String replacement;
        final String idBase;
        final String name;
        final String summary;
        final Set<String> distinctValues = new HashSet<>();
        String valuePair = "";
        int rows;
        int exampleRow = -1;

        Candidate(String regex, String replacement, String idBase, String name, String summary) {
            this.regex = regex;
            this.replacement = replacement;
            this.idBase = idBase;
            this.name = name;
            this.summary = summary;
        }
    }
}
