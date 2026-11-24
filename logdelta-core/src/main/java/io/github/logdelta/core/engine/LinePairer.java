// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Decides which removed and added lines inside one change block are "the same line, modified".
///
/// A naive side-by-side view pairs the first removed line with the first added line, and so
/// on, which often puts unrelated lines next to each other. Instead, this class computes a
/// token-based similarity for every candidate pair: a Dice coefficient over word multisets in
/// which each token is weighted by its rarity ([TokenWeights]), so shared log boilerplate
/// does not make unrelated messages look alike. It then chooses an order-preserving alignment
/// that maximises the total similarity of pairs above [#THRESHOLD]. Lines that find no
/// partner are shown as pure removals or additions.
///
/// The dynamic programme is `O(n*m)`. Blocks larger than [#MAX_CELLS] fall back to
/// pairing by position, still subject to the threshold.
final class LinePairer {

    /// Minimum similarity for two lines to be shown as a modification.
    static final double THRESHOLD = 0.45;

    private static final int MAX_CELLS = 250_000;

    private LinePairer() {
    }

    /// Returns pairs `{i, j}` of indices into `removed` and `added`, strictly
    /// increasing in both components.
    static List<int[]> pair(List<String> removed, List<String> added) {
        return pair(removed, added, TokenWeights.UNIFORM);
    }

    /// As [#pair(List,List)] with explicit token weights.
    static List<int[]> pair(List<String> removed, List<String> added, TokenWeights weights) {
        int n = removed.size();
        int m = added.size();
        List<int[]> out = new ArrayList<>();
        if (n == 0 || m == 0) {
            return out;
        }
        if ((long) n * m > MAX_CELLS) {
            // Too big for the dynamic programme: pair by position, tokenising one pair at a time so
            // that a huge change block does not hold millions of token maps at once.
            for (int i = 0; i < Math.min(n, m); i++) {
                String a = removed.get(i);
                String b = added.get(i);
                double sim = isStructured(a) && isStructured(b) ? structuredSimilarity(a, b)
                        : similarity(tokens(a), tokens(b), weights);
                if (sim >= THRESHOLD) {
                    out.add(new int[] {i, i});
                }
            }
            return out;
        }
        List<Map<String, Integer>> lt = new ArrayList<>(n);
        List<Map<String, Integer>> rt = new ArrayList<>(m);
        removed.forEach(s -> lt.add(tokens(s)));
        added.forEach(s -> rt.add(tokens(s)));

        double[][] score = new double[n + 1][m + 1];
        double[][] sim = new double[n][m];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                String ra = removed.get(i - 1);
                String ab = added.get(j - 1);
                double s = isStructured(ra) && isStructured(ab) ? structuredSimilarity(ra, ab)
                        : similarity(lt.get(i - 1), rt.get(j - 1), weights);
                sim[i - 1][j - 1] = s;
                double best = Math.max(score[i - 1][j], score[i][j - 1]);
                if (s >= THRESHOLD) {
                    best = Math.max(best, score[i - 1][j - 1] + s);
                }
                score[i][j] = best;
            }
        }
        int i = n;
        int j = m;
        while (i > 0 && j > 0) {
            double s = sim[i - 1][j - 1];
            if (s >= THRESHOLD && score[i][j] == score[i - 1][j - 1] + s) {
                out.addFirst(new int[] {i - 1, j - 1});
                i--;
                j--;
            } else if (score[i][j] == score[i - 1][j]) {
                i--;
            } else {
                j--;
            }
        }
        return out;
    }

    /// Unweighted Dice coefficient over token multisets, in `[0, 1]`.
    static double similarity(Map<String, Integer> a, Map<String, Integer> b) {
        return similarity(a, b, TokenWeights.UNIFORM);
    }

    /// Separator of the fields in the key of a structured (JSON/logfmt) line.
    static final char FIELD_SEPARATOR = '\u001f';

    private static boolean isStructured(String key) {
        return key.indexOf(FIELD_SEPARATOR) >= 0;
    }

    /// Similarity of two structured lines: half for sharing field names, half for sharing
    /// `name=value` pairs, so a line whose fields are the same but whose values changed still
    /// pairs with its old version.
    static double structuredSimilarity(String a, String b) {
        List<String> fa = List.of(a.split(String.valueOf(FIELD_SEPARATOR)));
        List<String> fb = List.of(b.split(String.valueOf(FIELD_SEPARATOR)));
        Map<String, Integer> namesA = new HashMap<>();
        Map<String, Integer> namesB = new HashMap<>();
        Map<String, Integer> pairsA = new HashMap<>();
        Map<String, Integer> pairsB = new HashMap<>();
        for (String f : fa) {
            namesA.merge(name(f), 1, Integer::sum);
            pairsA.merge(f, 1, Integer::sum);
        }
        for (String f : fb) {
            namesB.merge(name(f), 1, Integer::sum);
            pairsB.merge(f, 1, Integer::sum);
        }
        return 0.5 * dice(namesA, namesB, fa.size() + fb.size()) + 0.5 * dice(pairsA, pairsB, fa.size() + fb.size());
    }

    private static String name(String field) {
        int eq = field.indexOf('=');
        return eq < 0 ? field : field.substring(0, eq);
    }

    private static double dice(Map<String, Integer> a, Map<String, Integer> b, int total) {
        int common = 0;
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            common += Math.min(e.getValue(), b.getOrDefault(e.getKey(), 0));
        }
        return total == 0 ? 1.0 : 2.0 * common / total;
    }

    /// Weighted Dice coefficient over token multisets, in `[0, 1]`.
    ///
    /// A number that only one side has is a value (a count, an id, an amount) rather than a word
    /// of the message, so it costs no more than the floor weight; a number both sides share keeps
    /// its full weight, as it is good evidence that the lines belong together. That way
    /// `Charged ORD-111 amount=5` and `Charged ORD-222 amount=7` pair up, and so do `line 5` and
    /// `changed 5`.
    static double similarity(Map<String, Integer> a, Map<String, Integer> b, TokenWeights weights) {
        double sizeA = weightedSize(a, b, weights);
        double sizeB = weightedSize(b, a, weights);
        if (sizeA + sizeB == 0) {
            return 1.0;
        }
        double common = 0;
        Map<String, Integer> small = a.size() <= b.size() ? a : b;
        Map<String, Integer> large = small == a ? b : a;
        for (Map.Entry<String, Integer> e : small.entrySet()) {
            Integer other = large.get(e.getKey());
            if (other != null) {
                common += Math.min(e.getValue(), other) * weights.weight(e.getKey());
            }
        }
        return 2.0 * common / (sizeA + sizeB);
    }

    private static double weightedSize(Map<String, Integer> tokens, Map<String, Integer> other, TokenWeights weights) {
        double sum = 0;
        for (Map.Entry<String, Integer> e : tokens.entrySet()) {
            double w = weights == TokenWeights.UNIFORM || other.containsKey(e.getKey()) || !hasDigit(e.getKey())
                    ? weights.weight(e.getKey()) : Math.min(TokenWeights.FLOOR, weights.weight(e.getKey()));
            sum += e.getValue() * w;
        }
        return sum;
    }

    private static boolean hasDigit(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (Character.isDigit(token.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /// Splits on anything that is not a letter, digit, underscore or placeholder bracket.
    static Map<String, Integer> tokens(String s) {
        Map<String, Integer> out = new HashMap<>();
        int i = 0;
        while (i < s.length()) {
            while (i < s.length() && !isTokenChar(s.charAt(i))) {
                i++;
            }
            int j = i;
            while (j < s.length() && isTokenChar(s.charAt(j))) {
                j++;
            }
            if (j > i) {
                out.merge(s.substring(i, j), 1, Integer::sum);
            }
            i = j;
        }
        return out;
    }

    private static boolean isTokenChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '<' || c == '>';
    }
}
