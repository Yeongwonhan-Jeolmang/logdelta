// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.diff;

import io.github.logdelta.core.util.CancellationToken;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Patience diff, as popularised by Bram Cohen and used by `git diff --patience`.
///
/// 1. Strip the common prefix and suffix.
/// 1. Collect elements that occur exactly once in each range.
/// 1. Take the longest increasing subsequence of their positions (found with patience
///    sorting): these become anchors that are guaranteed to be matched.
/// 1. Recurse into the gaps between anchors. A gap without unique elements falls back to
///    [MyersDiff].
///
/// Anchoring on unique lines avoids the classic failure mode where a generic diff aligns
/// blank lines or closing braces instead of the meaningful content around them.
public final class PatienceDiff implements DiffAlgorithm {

    private final boolean minimal;

    /// Creates an instance whose Myers fallback uses the cost-limit heuristic.
    public PatienceDiff() {
        this(false);
    }

    /// @param minimal passed on to the Myers fallback
    public PatienceDiff(boolean minimal) {
        this.minimal = minimal;
    }

    @Override
    public String name() {
        return "Patience";
    }

    @Override
    public List<Edit> diff(int[] a, int[] b, CancellationToken token) {
        EditScript script = new EditScript();
        compare(a, 0, a.length, b, 0, b.length, script, token);
        return script.edits();
    }

    private void compare(int[] a, int aLo, int aHi, int[] b, int bLo, int bHi,
                         EditScript script, CancellationToken token) {
        token.throwIfCancelled();
        int prefix = 0;
        while (aLo + prefix < aHi && bLo + prefix < bHi && a[aLo + prefix] == b[bLo + prefix]) {
            prefix++;
        }
        script.equal(aLo, bLo, prefix);
        aLo += prefix;
        bLo += prefix;
        int suffix = 0;
        while (aHi - suffix > aLo && bHi - suffix > bLo && a[aHi - 1 - suffix] == b[bHi - 1 - suffix]) {
            suffix++;
        }
        aHi -= suffix;
        bHi -= suffix;

        if (aLo == aHi) {
            script.insert(aLo, bLo, bHi);
        } else if (bLo == bHi) {
            script.delete(aLo, aHi, bLo);
        } else {
            int[][] anchors = uniqueAnchors(a, aLo, aHi, b, bLo, bHi);
            if (anchors[0].length == 0) {
                MyersDiff.diffRange(a, aLo, aHi, b, bLo, bHi, script, token, minimal);
            } else {
                int pa = aLo;
                int pb = bLo;
                for (int i = 0; i < anchors[0].length; i++) {
                    int ai = anchors[0][i];
                    int bi = anchors[1][i];
                    compare(a, pa, ai, b, pb, bi, script, token);
                    script.equal(ai, bi, 1);
                    pa = ai + 1;
                    pb = bi + 1;
                }
                compare(a, pa, aHi, b, pb, bHi, script, token);
            }
        }
        script.equal(aHi, bHi, suffix);
    }

    /// Returns two parallel arrays `{aPositions, bPositions}` of anchors: elements unique in
    /// both ranges whose positions increase in both sequences, chosen to be as many as possible.
    static int[][] uniqueAnchors(int[] a, int aLo, int aHi, int[] b, int bLo, int bHi) {
        // value -> {countA, posA, countB, posB}
        Map<Integer, int[]> occ = new HashMap<>();
        for (int i = aLo; i < aHi; i++) {
            int[] o = occ.computeIfAbsent(a[i], _ -> new int[] {0, -1, 0, -1});
            o[0]++;
            o[1] = i;
        }
        for (int j = bLo; j < bHi; j++) {
            int[] o = occ.get(b[j]);
            if (o != null) {
                o[2]++;
                o[3] = j;
            }
        }
        // Candidates ordered by position in a.
        List<int[]> pairs = new ArrayList<>();
        for (int i = aLo; i < aHi; i++) {
            int[] o = occ.get(a[i]);
            if (o[0] == 1 && o[2] == 1) {
                pairs.add(new int[] {i, o[3]});
            }
        }
        if (pairs.isEmpty()) {
            return new int[][] {new int[0], new int[0]};
        }
        // Longest increasing subsequence on b positions (patience sorting).
        int n = pairs.size();
        int[] tails = new int[n]; // index into pairs of the smallest tail of each pile
        int[] prev = new int[n];
        int piles = 0;
        for (int i = 0; i < n; i++) {
            int bPos = pairs.get(i)[1];
            int lo = 0;
            int hi = piles;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (pairs.get(tails[mid])[1] < bPos) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            prev[i] = lo > 0 ? tails[lo - 1] : -1;
            tails[lo] = i;
            if (lo == piles) {
                piles++;
            }
        }
        int[] as = new int[piles];
        int[] bs = new int[piles];
        int k = tails[piles - 1];
        for (int i = piles - 1; i >= 0; i--) {
            as[i] = pairs.get(k)[0];
            bs[i] = pairs.get(k)[1];
            k = prev[k];
        }
        return new int[][] {as, bs};
    }
}
