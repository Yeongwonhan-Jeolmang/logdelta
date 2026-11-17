// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Inverse document frequency of tokens across all compared lines.
///
/// Log lines share a lot of boilerplate: timestamps, levels, thread and logger names. When
/// deciding whether two lines are "the same line, modified", such tokens say little; the message
/// words matter. Weighting each token by `ln((N + 1) / (df + 1))`, where `df` is the
/// number of lines containing it, makes ubiquitous tokens nearly free and rare tokens decisive.
/// A small floor keeps lines made only of common tokens comparable.
final class TokenWeights {

    /// Weights that treat every token equally (used when nothing else is available).
    static final TokenWeights UNIFORM = new TokenWeights(Map.of(), 0);

    private static final double FLOOR = 0.05;

    private final Map<String, Integer> documentFrequency;
    private final int documents;

    private TokenWeights(Map<String, Integer> documentFrequency, int documents) {
        this.documentFrequency = documentFrequency;
        this.documents = documents;
    }

    /// Computes document frequencies over the given comparison keys.
    static TokenWeights of(List<String> keys) {
        Map<String, Integer> df = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (String key : keys) {
            seen.clear();
            seen.addAll(LinePairer.tokens(key).keySet());
            for (String t : seen) {
                df.merge(t, 1, Integer::sum);
            }
        }
        return new TokenWeights(df, keys.size());
    }

    /// Weight of a token; `1.0` for uniform weights.
    double weight(String token) {
        if (documents == 0) {
            return 1.0;
        }
        int df = documentFrequency.getOrDefault(token, 0);
        return Math.max(FLOOR, Math.log((documents + 1.0) / (df + 1.0)));
    }
}
