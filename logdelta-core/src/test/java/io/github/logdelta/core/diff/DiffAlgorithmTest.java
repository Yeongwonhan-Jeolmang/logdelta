// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.diff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffAlgorithmTest {

    private static final List<DiffAlgorithm> ALGORITHMS = List.of(
            new MyersDiff(true), new MyersDiff(false), new PatienceDiff(true), new PatienceDiff(false));

    @Test
    @DisplayName("identical inputs produce a single EQUAL edit")
    void identical() {
        int[] a = {1, 2, 3, 4};
        for (DiffAlgorithm alg : ALGORITHMS) {
            List<Edit> edits = alg.diff(a, a.clone());
            assertEquals(List.of(new Edit(Edit.Type.EQUAL, 0, 4, 0, 4)), edits, alg.name());
        }
    }

    @Test
    @DisplayName("empty inputs are handled on either side")
    void empty() {
        for (DiffAlgorithm alg : ALGORITHMS) {
            assertEquals(List.of(), alg.diff(new int[0], new int[0]));
            assertEquals(List.of(new Edit(Edit.Type.INSERT, 0, 0, 0, 3)), alg.diff(new int[0], new int[] {1, 2, 3}));
            assertEquals(List.of(new Edit(Edit.Type.DELETE, 0, 2, 0, 0)), alg.diff(new int[] {1, 2}, new int[0]));
        }
    }

    @Test
    @DisplayName("Myers finds the textbook shortest edit script (D = 5)")
    void textbookExample() {
        // ABCABBA -> CBABAC from Myers' paper
        int[] a = {'A', 'B', 'C', 'A', 'B', 'B', 'A'};
        int[] b = {'C', 'B', 'A', 'B', 'A', 'C'};
        List<Edit> edits = new MyersDiff(true).diff(a, b);
        assertValid(a, b, edits);
        assertEquals(5, editDistance(edits));
    }

    @Test
    @DisplayName("minimal Myers matches the LCS-based edit distance on random inputs")
    void minimalOnRandomInputs() {
        Random rnd = new Random(42);
        for (int round = 0; round < 300; round++) {
            int[] a = randomSeq(rnd, rnd.nextInt(40), 6);
            int[] b = mutate(rnd, a, 6);
            List<Edit> edits = new MyersDiff(true).diff(a, b);
            assertValid(a, b, edits);
            assertEquals(a.length + b.length - 2 * lcs(a, b), editDistance(edits),
                    "round " + round + ": " + Arrays.toString(a) + " vs " + Arrays.toString(b));
        }
    }

    @Test
    @DisplayName("every algorithm produces a valid script on random inputs")
    void validOnRandomInputs() {
        Random rnd = new Random(7);
        for (int round = 0; round < 200; round++) {
            int[] a = randomSeq(rnd, rnd.nextInt(120), 20);
            int[] b = rnd.nextBoolean() ? mutate(rnd, a, 20) : randomSeq(rnd, rnd.nextInt(120), 20);
            for (DiffAlgorithm alg : ALGORITHMS) {
                assertValid(a, b, alg.diff(a, b));
            }
        }
    }

    @Test
    @DisplayName("the heuristic stays correct and fast on large, unrelated inputs")
    void heuristicOnLargeInputs() {
        Random rnd = new Random(1);
        int[] a = randomSeq(rnd, 20_000, 5_000);
        int[] b = randomSeq(rnd, 20_000, 5_000);
        long start = System.nanoTime();
        List<Edit> edits = new MyersDiff(false).diff(a, b);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertValid(a, b, edits);
        assertTrue(millis < 10_000, "took " + millis + " ms");
    }

    @Test
    @DisplayName("patience anchors on unique lines")
    void patienceAnchors() {
        int[] a = {1, 9, 2, 9, 3};
        int[] b = {3, 9, 1, 9, 2};
        int[][] anchors = PatienceDiff.uniqueAnchors(a, 0, a.length, b, 0, b.length);
        // unique in both: 1, 2, 3 at a-positions 0, 2, 4 and b-positions 2, 4, 0 -> LIS is (0,2),(2,4)
        assertArrayEquals(new int[] {0, 2}, anchors[0]);
        assertArrayEquals(new int[] {2, 4}, anchors[1]);
    }

    // --- helpers ---------------------------------------------------------------------------

    /// Checks that the script covers both sequences in order and that EQUAL ranges really match.
    static void assertValid(int[] a, int[] b, List<Edit> edits) {
        int ai = 0;
        int bi = 0;
        List<Integer> rebuilt = new ArrayList<>();
        for (Edit e : edits) {
            assertEquals(ai, e.aStart(), "a ranges must be contiguous");
            assertEquals(bi, e.bStart(), "b ranges must be contiguous");
            switch (e.type()) {
                case EQUAL -> {
                    assertEquals(e.aLength(), e.bLength());
                    for (int k = 0; k < e.aLength(); k++) {
                        assertEquals(a[e.aStart() + k], b[e.bStart() + k], "EQUAL range mismatch");
                        rebuilt.add(a[e.aStart() + k]);
                    }
                }
                case DELETE -> assertEquals(0, e.bLength());
                case INSERT -> {
                    assertEquals(0, e.aLength());
                    for (int k = e.bStart(); k < e.bEnd(); k++) {
                        rebuilt.add(b[k]);
                    }
                }
                default -> throw new AssertionError();
            }
            ai = e.aEnd();
            bi = e.bEnd();
        }
        assertEquals(a.length, ai);
        assertEquals(b.length, bi);
        assertArrayEquals(b, rebuilt.stream().mapToInt(Integer::intValue).toArray());
    }

    static int editDistance(List<Edit> edits) {
        return edits.stream().filter(e -> e.type() != Edit.Type.EQUAL).mapToInt(e -> e.aLength() + e.bLength()).sum();
    }

    static int lcs(int[] a, int[] b) {
        int[][] dp = new int[a.length + 1][b.length + 1];
        for (int i = 1; i <= a.length; i++) {
            for (int j = 1; j <= b.length; j++) {
                dp[i][j] = a[i - 1] == b[j - 1] ? dp[i - 1][j - 1] + 1 : Math.max(dp[i - 1][j], dp[i][j - 1]);
            }
        }
        return dp[a.length][b.length];
    }

    static int[] randomSeq(Random rnd, int n, int alphabet) {
        int[] s = new int[n];
        for (int i = 0; i < n; i++) {
            s[i] = rnd.nextInt(alphabet);
        }
        return s;
    }

    static int[] mutate(Random rnd, int[] a, int alphabet) {
        List<Integer> out = new ArrayList<>();
        for (int v : a) {
            int r = rnd.nextInt(10);
            if (r == 0) {
                continue;
            }
            if (r == 1) {
                out.add(rnd.nextInt(alphabet));
            }
            out.add(r == 2 ? rnd.nextInt(alphabet) : v);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }
}
