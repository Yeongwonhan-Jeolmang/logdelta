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
        List<Map<String, Integer>> lt = new ArrayList<>(n);
        List<Map<String, Integer>> rt = new ArrayList<>(m);
        removed.forEach(s -> lt.add(tokens(s)));
        added.forEach(s -> rt.add(tokens(s)));

        if ((long) n * m > MAX_CELLS) {
            for (int i = 0; i < Math.min(n, m); i++) {
                if (similarity(lt.get(i), rt.get(i), weights) >= THRESHOLD) {
                    out.add(new int[] {i, i});
                }
            }
            return out;
        }

        double[][] score = new double[n + 1][m + 1];
        double[][] sim = new double[n][m];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                double s = similarity(lt.get(i - 1), rt.get(j - 1), weights);
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

    /// Weighted Dice coefficient over token multisets, in `[0, 1]`.
    static double similarity(Map<String, Integer> a, Map<String, Integer> b, TokenWeights weights) {
        double sizeA = weightedSize(a, weights);
        double sizeB = weightedSize(b, weights);
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

    private static double weightedSize(Map<String, Integer> tokens, TokenWeights weights) {
        double sum = 0;
        for (Map.Entry<String, Integer> e : tokens.entrySet()) {
            sum += e.getValue() * weights.weight(e.getKey());
        }
        return sum;
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
