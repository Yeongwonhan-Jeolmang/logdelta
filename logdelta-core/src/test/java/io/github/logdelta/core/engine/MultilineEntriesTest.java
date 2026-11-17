// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.engine;

import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.RowKind;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultilineEntriesTest {

    private static final List<String> TRACE = List.of(
            "2026-01-01 10:00:01 ERROR request failed",
            "java.io.IOException: broken pipe",
            "\tat a.b.C.write(C.java:10)",
            "\tat a.b.D.send(D.java:20)");

    private static List<String> log(boolean traceFirst) {
        List<String> out = new ArrayList<>();
        if (traceFirst) {
            out.addAll(TRACE);
        }
        out.add("2026-01-01 10:00:02 INFO step one");
        out.add("2026-01-01 10:00:03 INFO step two");
        out.add("2026-01-01 10:00:04 INFO step three");
        if (!traceFirst) {
            out.addAll(TRACE);
        }
        return out;
    }

    private static DiffResult compare(List<String> l, List<String> r, boolean multiline) {
        return new LogDeltaEngine().compare(LogFile.ofLines("l", l), LogFile.ofLines("r", r),
                DiffOptions.builder().multilineEntries(multiline).build());
    }

    @Test
    @DisplayName("a moved stack trace moves as one entry")
    void movedEntry() {
        DiffResult r = compare(log(true), log(false), true);
        long moved = r.rows().stream().filter(DiffRow::isMoved).count();
        assertEquals(8, moved, "4 lines out, 4 lines in");
        assertEquals(4, r.stats().moved());
    }

    @Test
    @DisplayName("an entry only matches when all of its lines match")
    void entryMustMatchCompletely() {
        List<String> l = new ArrayList<>(TRACE);
        List<String> r = new ArrayList<>(TRACE);
        r.set(3, "\tat a.b.E.other(E.java:99)");
        DiffResult res = compare(l, r, true);
        assertTrue(res.rows().stream().anyMatch(DiffRow::isChange));
        // Inside the changed entry, identical lines are still shown as unchanged.
        assertEquals(RowKind.UNCHANGED, res.rows().getFirst().kind());
    }

    @Test
    @DisplayName("without timestamps, indented and 'Caused by' lines continue an entry")
    void withoutTimestamps() {
        List<String> lines = List.of("Exception in thread main", "\tat x.Y.z(Y.java:1)", "Caused by: boom", "next");
        Entries e = Entries.of(new LogDeltaEngine().compare(LogFile.ofLines("a", lines), LogFile.ofLines("b", lines),
                DiffOptions.defaults()).leftLines(), new long[] {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE,
                Long.MIN_VALUE});
        assertEquals(2, e.count());
        assertEquals(3, e.end(0));
    }
}
