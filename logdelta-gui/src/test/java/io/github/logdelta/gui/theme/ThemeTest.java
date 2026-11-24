// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.theme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Palettes for colour-blind users and the high-contrast theme.
class ThemeTest {

    private static double luminance(Color c) {
        double[] v = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
        for (int i = 0; i < 3; i++) {
            v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    static double contrast(Color a, Color b) {
        double x = luminance(a);
        double y = luminance(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    @Test
    @DisplayName("each theme picks its palette")
    void palettes() {
        assertSame(DiffPalette.LIGHT, Theme.LIGHT.palette());
        assertSame(DiffPalette.LIGHT_CVD, Theme.LIGHT.palette(true));
        assertSame(DiffPalette.DARK_CVD, Theme.DARK.palette(true));
        assertSame(DiffPalette.HIGH_CONTRAST, Theme.HIGH_CONTRAST.palette(true));
        assertTrue(Theme.HIGH_CONTRAST.isDark());
        assertFalse(Theme.LIGHT.isDark());
        assertEquals(Theme.HIGH_CONTRAST, Theme.parse(Theme.HIGH_CONTRAST.name()));
    }

    @Test
    @DisplayName("text is readable on every palette; high contrast meets WCAG AAA")
    void readable() {
        for (DiffPalette p : new DiffPalette[] {DiffPalette.LIGHT, DiffPalette.DARK, DiffPalette.LIGHT_CVD,
            DiffPalette.DARK_CVD, DiffPalette.HIGH_CONTRAST}) {
            assertTrue(contrast(p.text(), p.background()) >= 4.5, p.toString());
            assertTrue(contrast(p.text(), p.removed()) >= 4.5);
            assertTrue(contrast(p.text(), p.added()) >= 4.5);
        }
        assertTrue(contrast(DiffPalette.HIGH_CONTRAST.text(), DiffPalette.HIGH_CONTRAST.background()) >= 7);
    }

    @Test
    @DisplayName("colour-blind-safe markers do not rely on red against green")
    void colourBlindSafe() {
        for (DiffPalette p : new DiffPalette[] {DiffPalette.LIGHT_CVD, DiffPalette.DARK_CVD}) {
            Color removed = p.removedMarker();
            Color added = p.addedMarker();
            // Blue/orange: the blue channel tells them apart, which red-green blindness keeps.
            assertTrue(Math.abs(removed.getBlue() - added.getBlue()) > 80, p.toString());
        }
    }
}
