// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.diff;

import io.github.logdelta.core.util.CancellationToken;

import java.util.Arrays;
import java.util.List;

/// Eugene W. Myers' "An O(ND) Difference Algorithm and Its Variations" (1986), using the
/// linear-space refinement.
///
/// The comparison runs a forward and a reverse search for the shortest edit path at the same
/// time. When the two searches meet, the problem is split at that point and both halves are
/// solved recursively. Common prefixes and suffixes are stripped first, which makes the typical
/// log comparison (mostly identical, a few changed regions) very cheap.
///
/// Unless `minimal` is requested, a cost limit bounds the search depth. When it is hit,
/// the algorithm splits at the furthest point the forward search reached. The result is still a
/// correct edit script, only possibly not the shortest one. This keeps the worst case at roughly
/// `O((N+M) * limit)` for inputs that have almost nothing in common.
public final class MyersDiff implements DiffAlgorithm {

    private static final int MIN_COST_LIMIT = 256;

    private final boolean minimal;

    /// Creates an instance that uses the cost-limit heuristic.
    public MyersDiff() {
        this(false);
    }

    /// @param minimal if `true`, always find a shortest edit script regardless of cost
    public MyersDiff(boolean minimal) {
        this.minimal = minimal;
    }

    @Override
    public String name() {
        return "Myers";
    }

    @Override
    public List<Edit> diff(int[] a, int[] b, CancellationToken token) {
        EditScript script = new EditScript();
        new Run(a, b, script, token, minimal).compare(0, a.length, 0, b.length);
        return script.edits();
    }

    /// Diffs a sub-range and appends to an existing script; used by [PatienceDiff].
    static void diffRange(int[] a, int aLo, int aHi, int[] b, int bLo, int bHi,
                          EditScript script, CancellationToken token, boolean minimal) {
        new Run(a, b, script, token, minimal).compare(aLo, aHi, bLo, bHi);
    }

    private static final class Run {
        private final int[] a;
        private final int[] b;
        private final EditScript script;
        private final CancellationToken token;
        private final boolean minimal;

        Run(int[] a, int[] b, EditScript script, CancellationToken token, boolean minimal) {
            this.a = a;
            this.b = b;
            this.script = script;
            this.token = token;
            this.minimal = minimal;
        }

        void compare(int aLo, int aHi, int bLo, int bHi) {
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
                bisect(aLo, aHi, bLo, bHi);
            }
            script.equal(aHi, bHi, suffix);
        }

        /// Finds a point on an optimal (or, past the cost limit, a good) edit path and recurses on
        /// both sides of it. Both ranges are non-empty and share no common prefix or suffix.
        private void bisect(int aLo, int aHi, int bLo, int bHi) {
            final int n = aHi - aLo;
            final int m = bHi - bLo;
            final int maxD = (n + m + 1) / 2;
            final int limit = minimal ? maxD
                    : Math.min(maxD, Math.max(MIN_COST_LIMIT, (int) (2 * Math.sqrt((double) n + m))));
            final int offset = limit + 1;
            final int length = 2 * limit + 3;
            int[] vf = new int[length];
            int[] vb = new int[length];
            Arrays.fill(vf, -1);
            Arrays.fill(vb, -1);
            vf[offset + 1] = 0;
            vb[offset + 1] = 0;
            final int delta = n - m;
            final boolean front = (delta & 1) != 0;
            int kfStart = 0;
            int kfEnd = 0;
            int kbStart = 0;
            int kbEnd = 0;

            for (int d = 0; d < maxD; d++) {
                if ((d & 63) == 0) {
                    token.throwIfCancelled();
                }
                // Forward search.
                for (int k = -d + kfStart; k <= d - kfEnd; k += 2) {
                    int ko = offset + k;
                    int x = (k == -d || (k != d && vf[ko - 1] < vf[ko + 1])) ? vf[ko + 1] : vf[ko - 1] + 1;
                    int y = x - k;
                    while (x < n && y < m && a[aLo + x] == b[bLo + y]) {
                        x++;
                        y++;
                    }
                    vf[ko] = x;
                    if (x > n) {
                        kfEnd += 2;
                    } else if (y > m) {
                        kfStart += 2;
                    } else if (front) {
                        int kbo = offset + delta - k;
                        if (kbo >= 0 && kbo < length && vb[kbo] != -1 && x >= n - vb[kbo]) {
                            split(aLo, aHi, bLo, bHi, x, y);
                            return;
                        }
                    }
                }
                // Reverse search (x and y count from the ends of the ranges).
                for (int k = -d + kbStart; k <= d - kbEnd; k += 2) {
                    int ko = offset + k;
                    int x = (k == -d || (k != d && vb[ko - 1] < vb[ko + 1])) ? vb[ko + 1] : vb[ko - 1] + 1;
                    int y = x - k;
                    while (x < n && y < m && a[aHi - 1 - x] == b[bHi - 1 - y]) {
                        x++;
                        y++;
                    }
                    vb[ko] = x;
                    if (x > n) {
                        kbEnd += 2;
                    } else if (y > m) {
                        kbStart += 2;
                    } else if (!front) {
                        int kfo = offset + delta - k;
                        if (kfo >= 0 && kfo < length && vf[kfo] != -1) {
                            int fx = vf[kfo];
                            int fy = fx - (kfo - offset);
                            if (fx >= n - x) {
                                split(aLo, aHi, bLo, bHi, fx, fy);
                                return;
                            }
                        }
                    }
                }
                if (limit < maxD && d >= limit - 1) {
                    if (splitAtFurthest(aLo, aHi, bLo, bHi, d, vf, offset, kfStart, kfEnd)) {
                        return;
                    }
                    break;
                }
            }
            // No split point found (only possible after giving up): replace the whole range.
            script.delete(aLo, aHi, bLo);
            script.insert(aHi, bLo, bHi);
        }

        /// Heuristic split at the forward diagonal that progressed furthest.
        private boolean splitAtFurthest(int aLo, int aHi, int bLo, int bHi, int d, int[] vf,
                                        int offset, int kStart, int kEnd) {
            int n = aHi - aLo;
            int m = bHi - bLo;
            int bestX = -1;
            int bestY = -1;
            for (int k = -d + kStart; k <= d - kEnd; k += 2) {
                int x = vf[offset + k];
                int y = x - k;
                if (x < 0 || x > n || y < 0 || y > m) {
                    continue;
                }
                if (x + y > bestX + bestY) {
                    bestX = x;
                    bestY = y;
                }
            }
            if (bestX < 0 || bestX + bestY == 0 || (bestX == n && bestY == m)) {
                return false;
            }
            split(aLo, aHi, bLo, bHi, bestX, bestY);
            return true;
        }

        private void split(int aLo, int aHi, int bLo, int bHi, int x, int y) {
            compare(aLo, aLo + x, bLo, bLo + y);
            compare(aLo + x, aHi, bLo + y, bHi);
        }
    }
}
