// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffCanvasTest {

    @Test
    @DisplayName("the divider splits the width and stays within limits")
    void split() {
        DiffCanvas canvas = new DiffCanvas();
        canvas.setSize(1010, 400);
        assertEquals(500, canvas.leftPaneWidth());
        assertEquals(500, canvas.rightPaneWidth());

        canvas.setSplit(0.3);
        assertEquals(300, canvas.leftPaneWidth());
        assertEquals(310, canvas.rightPaneX());
        assertEquals(700, canvas.rightPaneWidth());

        canvas.setSplit(0.0);
        assertEquals(DiffCanvas.MIN_SPLIT, canvas.split());
        canvas.setSplit(5);
        assertEquals(DiffCanvas.MAX_SPLIT, canvas.split());
        assertTrue(canvas.rightPaneWidth() > 0, "the narrow pane never disappears");
    }

    @Test
    @DisplayName("listeners hear about divider moves, but not about no-op changes")
    void notifies() {
        DiffCanvas canvas = new DiffCanvas();
        List<Double> seen = new ArrayList<>();
        canvas.addListener(new DiffCanvas.Listener() {
            @Override
            public void splitChanged(double split) {
                seen.add(split);
            }
        });
        canvas.setSplit(0.5);
        canvas.setSplit(0.7);
        canvas.setSplit(0.7);
        assertEquals(List.of(0.7), seen);
    }
}
