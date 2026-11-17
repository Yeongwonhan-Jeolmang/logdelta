// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.model;

import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.engine.LogDeltaEngine;
import io.github.logdelta.core.io.LogFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewFilterTest {

    private static DiffResult result() {
        return new LogDeltaEngine().compare(
                LogFile.ofLines("good", List.of("INFO start", "INFO a", "ERROR old failure", "INFO b", "INFO c",
                        "INFO stop")),
                LogFile.ofLines("bad", List.of("INFO start", "INFO a", "ERROR old failure", "INFO b",
                        "WARN disk slow", "INFO c", "INFO stop")),
                DiffOptions.defaults());
    }

    private static List<Integer> lines(DiffViewModel m) {
        return java.util.stream.IntStream.range(0, m.size()).filter(v -> m.get(v) instanceof ViewRow.Line)
                .mapToObj(v -> m.get(v).firstRow()).toList();
    }

    @Test
    @DisplayName("filters keep matching rows and mark the hidden runs as gaps")
    void modes() {
        DiffResult r = result();
        DiffViewModel changes = new DiffViewModel(r, true, 3, new ViewFilter(ViewFilter.Mode.CHANGES, ""));
        assertEquals(List.of(4), lines(changes));
        assertInstanceOf(ViewRow.Gap.class, changes.get(0));
        assertEquals(1, changes.visibleRowCount());

        DiffViewModel problems = new DiffViewModel(r, true, 3, new ViewFilter(ViewFilter.Mode.PROBLEMS, ""));
        assertEquals(List.of(4), lines(problems));

        DiffViewModel errors = new DiffViewModel(r, true, 3, new ViewFilter(ViewFilter.Mode.ERRORS, ""));
        assertEquals(List.of(2), lines(errors));
        assertNull(errors.diffRow(0));
        assertEquals(-1, errors.reveal(0), "rows hidden by a filter cannot be revealed");

        DiffViewModel text = new DiffViewModel(r, true, 3, new ViewFilter(ViewFilter.Mode.ALL, "  INFO B "));
        assertEquals(List.of(3), lines(text));
        assertFalse(ViewFilter.NONE.active());
        assertTrue(new ViewFilter(ViewFilter.Mode.ALL, "x").active());
    }

    @Test
    @DisplayName("sources parse paths and URLs")
    void sources() {
        assertInstanceOf(Source.Url.class, Source.parse(" https://ci.example.com/a/b.log "));
        assertEquals("b.log", Source.parse("https://ci.example.com/a/b.log").displayName());
        Source f = Source.parse("logs/app.log");
        assertEquals(Path.of("logs/app.log"), f.path());
        assertEquals("app.log", f.displayName());
        assertNull(new Source.Text(LogFile.ofText("Pasted", "x")).spec());
    }
}
