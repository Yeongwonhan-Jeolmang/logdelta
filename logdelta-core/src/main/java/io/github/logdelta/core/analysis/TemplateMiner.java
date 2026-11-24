// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Groups log messages into **templates** such as `Retry <*> for order <*>`, in the spirit of
/// the Drain log parser, so that counts can be compared per kind of message rather than per
/// exact text.
///
/// Mining works in two passes over the messages (which have already been normalized, so ids and
/// timestamps are masked):
///
/// 1. **Shape.** Messages are split into tokens; `key=value` tokens are split after the `=`.
///    Tokens that look variable (they contain a digit, or are quoted) become `<*>`. Messages
///    with the same severity, the same number of tokens and the same tokens are one cluster.
/// 2. **Cardinality.** Within clusters of the same severity and length, a position that holds
///    at least [#MIN_DISTINCT_WORDS] different words, with everything else equal, is a variable
///    too (user names, queue names), and those clusters are merged. Positions with only two or
///    so values (`succeeded` / `failed`) stay literal, so a success turning into a failure is
///    never hidden inside one template.
///
/// The result is deterministic for a given input order.
public final class TemplateMiner {

    /// Placeholder for a variable token.
    public static final String WILDCARD = "<*>";
    /// Distinct words a position needs before it is treated as a variable.
    public static final int MIN_DISTINCT_WORDS = 3;
    /// Messages longer than this many tokens keep their first tokens only.
    static final int MAX_TOKENS = 40;

    private final Map<String, Integer> idsByShape = new HashMap<>();
    private final List<String[]> shapes = new ArrayList<>();
    private final List<String> severities = new ArrayList<>();
    private final List<boolean[]> glue = new ArrayList<>();
    private int[] assignments = new int[256];
    private int count;
    private int[] canonical;
    private List<String> texts;

    /// Adds a message and returns its provisional cluster number (valid after [#finish()] via
    /// [#templateOf(int)]).
    ///
    /// @param severity severity label; messages of different severity never share a template
    /// @param message  normalized message text
    public int add(String severity, String message) {
        if (canonical != null) {
            throw new IllegalStateException("finish() was already called");
        }
        List<String> tokens = new ArrayList<>();
        List<Boolean> glued = new ArrayList<>();
        tokenize(message, tokens, glued);
        StringBuilder key = new StringBuilder(severity).append('\u0001');
        for (int i = 0; i < tokens.size(); i++) {
            key.append(tokens.get(i)).append(glued.get(i) ? '\u0002' : '\u0003');
        }
        String k = key.toString();
        Integer id = idsByShape.get(k);
        if (id == null) {
            id = shapes.size();
            idsByShape.put(k, id);
            shapes.add(tokens.toArray(String[]::new));
            severities.add(severity);
            boolean[] g = new boolean[glued.size()];
            for (int i = 0; i < g.length; i++) {
                g[i] = glued.get(i);
            }
            glue.add(g);
        }
        if (count == assignments.length) {
            assignments = java.util.Arrays.copyOf(assignments, count * 2);
        }
        assignments[count] = id;
        return count++;
    }

    /// Merges clusters (pass 2). Call once after all messages were added.
    public void finish() {
        int n = shapes.size();
        canonical = new int[n];
        for (int i = 0; i < n; i++) {
            canonical[i] = i;
        }
        String[][] current = new String[n][];
        for (int i = 0; i < n; i++) {
            current[i] = shapes.get(i).clone();
        }
        // Group cluster ids by severity + length + glue pattern.
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            StringBuilder g = new StringBuilder(severities.get(i)).append('|').append(current[i].length).append('|');
            for (boolean b : glue.get(i)) {
                g.append(b ? '1' : '0');
            }
            groups.computeIfAbsent(g.toString(), _ -> new ArrayList<>()).add(i);
        }
        for (List<Integer> group : groups.values()) {
            if (group.size() < MIN_DISTINCT_WORDS) {
                continue;
            }
            int len = current[group.getFirst()].length;
            for (int p = 0; p < len; p++) {
                Map<String, List<Integer>> bySignature = new LinkedHashMap<>();
                for (int id : group) {
                    if (find(id) != id) {
                        continue;
                    }
                    String[] t = current[id];
                    if (t[p].equals(WILDCARD)) {
                        continue;
                    }
                    bySignature.computeIfAbsent(signatureWithout(t, p), _ -> new ArrayList<>()).add(id);
                }
                for (List<Integer> same : bySignature.values()) {
                    if (same.size() < MIN_DISTINCT_WORDS) {
                        continue;
                    }
                    int root = same.getFirst();
                    current[root][p] = WILDCARD;
                    for (int k = 1; k < same.size(); k++) {
                        canonical[same.get(k)] = root;
                    }
                }
            }
        }
        texts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            texts.add(render(current[find(i)], glue.get(find(i))));
        }
    }

    /// Template number of the message added as number `index` (0-based, in [#add] order).
    /// Messages with the same number share a template.
    public int templateOf(int index) {
        requireFinished();
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(index);
        }
        return find(assignments[index]);
    }

    /// Template text of a template number.
    public String text(int template) {
        requireFinished();
        return texts.get(template);
    }

    /// Number of messages added.
    public int size() {
        return count;
    }

    private void requireFinished() {
        if (canonical == null) {
            throw new IllegalStateException("finish() has not been called");
        }
    }

    private int find(int id) {
        int r = id;
        while (canonical[r] != r) {
            r = canonical[r];
        }
        return r;
    }

    private static String signatureWithout(String[] t, int p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length; i++) {
            sb.append(i == p ? "\u0000" : t[i]).append('\u0001');
        }
        return sb.toString();
    }

    private static String render(String[] tokens, boolean[] glued) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0 && !glued[i - 1]) {
                sb.append(' ');
            }
            sb.append(tokens[i]);
        }
        return sb.toString();
    }

    /// Splits a message into template tokens. `glued` says, per token, whether the next token
    /// follows without a space (the value after `key=`).
    static void tokenize(String message, List<String> tokens, List<Boolean> glued) {
        int i = 0;
        int n = message.length();
        while (i < n && tokens.size() < MAX_TOKENS) {
            while (i < n && Character.isWhitespace(message.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int start = i;
            while (i < n && !Character.isWhitespace(message.charAt(i))) {
                i++;
            }
            String word = message.substring(start, i);
            int eq = splitPoint(word);
            if (eq > 0 && eq < word.length()) {
                tokens.add(word.substring(0, eq));
                glued.add(true);
                tokens.add(classify(word.substring(eq)));
                glued.add(false);
            } else {
                tokens.add(classify(word));
                glued.add(false);
            }
        }
    }

    /// Index just after the `=` or `:` of a `key=value` word, or `-1`.
    private static int splitPoint(String word) {
        for (int i = 1; i < word.length() - 1; i++) {
            char c = word.charAt(i);
            if (c == '=' || (c == ':' && i + 1 < word.length() && word.charAt(i + 1) != '/')) {
                return i + 1;
            }
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '.') {
                return -1;
            }
        }
        return -1;
    }

    private static String classify(String token) {
        if (token.length() >= 2 && token.startsWith("<") && token.endsWith(">")) {
            return token; // already masked by a rule: a stable placeholder
        }
        char first = token.charAt(0);
        if (first == '\'' || first == '"' || first == '`') {
            return WILDCARD;
        }
        for (int i = 0; i < token.length(); i++) {
            if (Character.isDigit(token.charAt(i))) {
                return WILDCARD;
            }
        }
        return token;
    }
}
