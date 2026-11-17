// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.engine;

import io.github.logdelta.core.diff.Algorithm;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.Hunk;
import io.github.logdelta.core.model.RowKind;
import io.github.logdelta.core.util.CancellationToken;
import io.github.logdelta.core.util.CancelledException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogDeltaEngineTest {

    private final LogDeltaEngine engine = new LogDeltaEngine();

    private DiffResult compare(List<String> left, List<String> right, DiffOptions options) {
        return engine.compare(LogFile.ofLines("left", left), LogFile.ofLines("right", right), options);
    }

    private DiffResult compare(List<String> left, List<String> right) {
        return compare(left, right, DiffOptions.defaults());
    }

    @Test
    @DisplayName("logs that differ only in volatile values are identical")
    void normalizedIdentical() {
        DiffResult r = compare(
                List.of("2026-01-01 10:00:00 start id=3f2b8c1e-9a4d-4e7b-8c2a-1d5e6f7a8b9c", "done in 12ms"),
                List.of("2026-02-03 11:22:33 start id=0a7a4223-ca3b-4cbb-a682-bfdf96f72015", "done in 950ms"));
        assertTrue(r.isIdentical());
        assertEquals(1.0, r.stats().similarity());
        assertEquals(-1, r.insights().firstDivergenceRow());
        assertTrue(r.hunks(3).isEmpty());
    }

    @Test
    @DisplayName("similar lines are paired as modifications, unrelated ones are not")
    void pairing() {
        DiffResult r = compare(
                List.of("header", "connecting to db primary", "footer"),
                List.of("header", "connecting to db replica", "totally unrelated output here", "footer"));
        List<RowKind> kinds = r.rows().stream().map(DiffRow::kind).toList();
        assertEquals(List.of(RowKind.UNCHANGED, RowKind.MODIFIED, RowKind.ADDED, RowKind.UNCHANGED), kinds);
        DiffRow mod = r.rows().get(1);
        assertFalse(mod.inline().isEmpty());
        assertEquals("primary", r.left(mod).text().substring(mod.inline().left().get(0).start()));
    }

    @Test
    @DisplayName("a line that moved is linked to its counterpart")
    void moves() {
        DiffResult r = compare(
                List.of("cache warmed with 1204 entries", "a", "b", "c"),
                List.of("a", "b", "c", "cache warmed with 1204 entries"));
        assertEquals(1, r.stats().moved());
        DiffRow removed = r.rows().stream().filter(x -> x.kind() == RowKind.REMOVED).findFirst().orElseThrow();
        DiffRow added = r.rows().get(removed.movedPeer());
        assertEquals(RowKind.ADDED, added.kind());
        assertEquals(r.rows().indexOf(removed), added.movedPeer());

        DiffResult noMoves = compare(List.of("cache warmed with 1204 entries", "a"), List.of("a",
                "cache warmed with 1204 entries"), DiffOptions.builder().detectMoves(false).build());
        assertEquals(0, noMoves.stats().moved());
    }

    @Test
    @DisplayName("ignored lines are excluded but keep original line numbers")
    void ignorePatterns() {
        DiffOptions o = DiffOptions.builder().addIgnorePattern("heartbeat").build();
        DiffResult r = compare(List.of("a", "heartbeat 1", "b"), List.of("a", "b", "heartbeat 2", "c"), o);
        assertEquals(1, r.stats().leftIgnored());
        assertEquals(1, r.stats().rightIgnored());
        assertEquals(1, r.stats().added());
        DiffRow added = r.rows().stream().filter(DiffRow::isChange).findFirst().orElseThrow();
        assertEquals(4, r.right(added).number());
    }

    @Test
    @DisplayName("rows cover every compared line exactly once, in order")
    void rowsCoverInput() {
        List<String> left = new ArrayList<>();
        List<String> right = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            left.add("line " + (i % 7 == 0 ? "x" + i : i));
            if (i % 11 != 0) {
                right.add("line " + (i % 5 == 0 ? "y" + i : i));
            }
        }
        for (Algorithm alg : Algorithm.values()) {
            DiffResult r = compare(left, right, DiffOptions.builder().algorithm(alg).build());
            int l = 0;
            int rr = 0;
            for (DiffRow row : r.rows()) {
                if (row.hasLeft()) {
                    assertEquals(l++, row.left());
                }
                if (row.hasRight()) {
                    assertEquals(rr++, row.right());
                }
            }
            assertEquals(left.size(), l);
            assertEquals(right.size(), rr);
        }
    }

    @Test
    @DisplayName("hunks merge overlapping context")
    void hunks() {
        List<String> left = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            left.add("line " + i);
        }
        List<String> right = new ArrayList<>(left);
        right.set(5, "changed 5");
        right.set(8, "changed 8");
        right.set(25, "changed 25");
        DiffResult r = compare(left, right);
        List<Hunk> hunks = r.hunks(2);
        assertEquals(2, hunks.size());
        assertEquals(new Hunk(3, 11), hunks.get(0));
        assertEquals(new Hunk(23, 28), hunks.get(1));
        assertEquals(List.of(5, 8, 25), r.changeBlockStarts());
    }

    @Test
    @DisplayName("new errors are reported with their line numbers")
    void insights() {
        DiffResult r = compare(
                List.of("INFO start", "INFO work", "INFO stop"),
                List.of("INFO start", "ERROR boom", "java.lang.IllegalStateException: bad", "\tat a.b.C.d(C.java:1)",
                        "INFO work", "INFO stop"));
        assertEquals(1, r.insights().newProblems().size());
        assertEquals(2, r.insights().newProblems().get(0).lineNumber());
        assertEquals(3, r.insights().newProblems().get(0).lines());
        assertEquals(1, r.insights().firstDivergenceRow());
    }

    @Test
    @DisplayName("progress is reported and cancellation stops the comparison")
    void progressAndCancel() {
        List<Double> fractions = new ArrayList<>();
        engine.compare(LogFile.ofLines("l", List.of("a")), LogFile.ofLines("r", List.of("b")), DiffOptions.defaults(),
                (_, f) -> fractions.add(f), CancellationToken.NEVER);
        assertEquals(1.0, fractions.get(fractions.size() - 1));

        CancellationToken token = new CancellationToken();
        token.cancel();
        assertThrows(CancelledException.class, () -> engine.compare(LogFile.ofLines("l", List.of("a")),
                LogFile.ofLines("r", List.of("b")), DiffOptions.defaults(), (_, _) -> { }, token));
    }

    @Test
    @DisplayName("line pairing similarity behaves sensibly")
    void similarity() {
        assertEquals(1.0, LinePairer.similarity(LinePairer.tokens("a b c"), LinePairer.tokens("c b a")));
        assertEquals(0.0, LinePairer.similarity(LinePairer.tokens("a b"), LinePairer.tokens("x y")));
        assertEquals(Map.of("<N>", 2, "x", 1), LinePairer.tokens("<N>-x <N>"));
    }
}
