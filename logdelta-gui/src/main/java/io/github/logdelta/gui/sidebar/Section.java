// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.sidebar;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;

/// Small layout helpers shared by the sidebar panels.
final class Section {

    private Section() {
    }

    /// An upper-case section caption in a muted colour that follows the current theme.
    static JLabel caption(String text) {
        JLabel l = new JLabel(text.toUpperCase(java.util.Locale.ROOT)) {
            @Override
            public void updateUI() {
                super.updateUI();
                setFont(getFont().deriveFont(Font.BOLD, Math.max(10f, getFont().getSize2D() - 2f)));
                setForeground(muted());
            }

            @Override
            public void setText(String t) {
                super.setText(t == null ? null : t.toUpperCase(java.util.Locale.ROOT));
            }
        };
        l.setBorder(BorderFactory.createEmptyBorder(14, 0, 6, 0));
        l.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return l;
    }

    /// Text colour blended 40% towards the background.
    static Color muted() {
        Color fg = UIManager.getColor("Label.foreground");
        Color bg = UIManager.getColor("Panel.background");
        if (fg == null || bg == null) {
            return Color.GRAY;
        }
        return new Color((fg.getRed() * 6 + bg.getRed() * 4) / 10, (fg.getGreen() * 6 + bg.getGreen() * 4) / 10,
                (fg.getBlue() * 6 + bg.getBlue() * 4) / 10);
    }

    /// A vertical container whose children are left-aligned.
    static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        p.setOpaque(false);
        return p;
    }

    /// Prevents a component from being stretched vertically by BoxLayout.
    static <T extends JComponent> T compact(T c) {
        c.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        Dimension pref = c.getPreferredSize();
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
        return c;
    }
}
