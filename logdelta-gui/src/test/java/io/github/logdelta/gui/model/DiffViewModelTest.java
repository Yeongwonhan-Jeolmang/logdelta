// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.model;

import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.engine.LogDeltaEngine;
import io.github.logdelta.core.io.LogFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffViewModelTest {

    /// 100 lines with changes at rows 10 and 80.
    private static DiffResult result() {
        List<String> left = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            left.add("line " + i);
        }
        List<String> right = new ArrayList<>(left);
        right.set(10, "line 10 changed");
        right.set(80, "line 80 changed");
        return new LogDeltaEngine().compare(LogFile.ofLines("l", left), LogFile.ofLines("r", right),
                DiffOptions.defaults());
    }

    @Test
    @DisplayName("without collapsing every row is visible")
    void noCollapse() {
        DiffViewModel m = new DiffViewModel(result(), false, 3);
        assertEquals(result().rows().size(), m.size());
        assertFalse(m.hasFolds());
    }

    @Test
    @DisplayName("unchanged regions outside the context become folds")
    void folds() {
        DiffResult r = result();
        DiffViewModel m = new DiffViewModel(r, true, 2);
        // fold [0,8) + rows 8..12 + fold [13,78) + rows 78..82 + fold [83,100)
        assertEquals(3 + 5 + 5, m.size());
        assertEquals(new ViewRow.Fold(0, 8), m.get(0));
        assertEquals(8, ((ViewRow.Fold) m.get(0)).hiddenCount());
        assertEquals(-1, m.viewIndexOf(50));
        assertEquals(2, m.changeStarts().length);
    }

    @Test
    @DisplayName("revealing a hidden row expands only its fold")
    void reveal() {
        DiffViewModel m = new DiffViewModel(result(), true, 2);
        int before = m.size();
        int v = m.reveal(50);
        assertTrue(v >= 0);
        assertEquals(50, m.get(v).firstRow());
        assertEquals(before - 1 + 65, m.size());
        assertTrue(m.hasFolds(), "other folds stay collapsed");
        m.expandAll();
        assertFalse(m.hasFolds());
    }

    @Test
    @DisplayName("navigation between change blocks")
    void navigation() {
        DiffViewModel m = new DiffViewModel(result(), true, 2);
        int first = m.nextChange(-1);
        int second = m.nextChange(first);
        assertEquals(10, m.get(first).firstRow());
        assertEquals(80, m.get(second).firstRow());
        assertEquals(-1, m.nextChange(second));
        assertEquals(first, m.previousChange(second));
    }

    @Test
    @DisplayName("search finds matches on both sides and reports invalid regexes")
    void search() {
        DiffResult r = result();
        SearchModel s = SearchModel.search(r, "changed", false, true, false);
        assertEquals(2, s.size());
        assertFalse(s.matches().get(0).left());
        assertEquals(10, s.matches().get(0).row());
        assertEquals(1, s.matchesInRow(80).size());
        assertEquals(200, SearchModel.search(r, "LINE", false, false, false).size());
        assertEquals(0, SearchModel.search(r, "LINE", false, true, false).size());
        assertEquals(2, SearchModel.search(r, "\\d+ changed", true, true, false).size());
        assertTrue(SearchModel.search(r, "(", true, true, false).error() != null);
    }
}
