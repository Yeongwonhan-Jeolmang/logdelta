// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.view;

import io.github.logdelta.core.Version;
import io.github.logdelta.gui.settings.AppSettings.RecentPair;
import io.github.logdelta.gui.theme.DiffPalette;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

import static io.github.logdelta.gui.i18n.I18n.tr;

/// Shown until two files are selected: explains what to do, offers the bundled example and
/// lists recent comparisons.
public final class WelcomePanel extends JPanel {

    private final JPanel recentList = new JPanel();
    private final Consumer<RecentPair> onRecent;
    private DiffPalette palette = DiffPalette.LIGHT;

    /// @param onOpen    opens a file chooser
    /// @param onExample loads the bundled example
    /// @param onRecent  re-opens a recent comparison
    /// @param more      further ways to start, shown as links: label and action
    public WelcomePanel(Runnable onOpen, Runnable onExample, Consumer<RecentPair> onRecent,
                        java.util.Map<String, Runnable> more) {
        super(new GridBagLayout());
        this.onRecent = onRecent;

        JPanel card = new JPanel();
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        JLabel title = new JLabel(Version.NAME);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        JLabel tagline = new JLabel(tr("Compare two logs while ignoring what always changes."));
        tagline.setFont(tagline.getFont().deriveFont(15f));
        JLabel hint = new JLabel("<html><div style='width:420px'>" + tr("Timestamps, UUIDs, thread ids, memory "
                + "addresses and other run-specific values are masked before comparing, so only real "
                + "differences remain. Drop two log files anywhere in this window to start.") + "</div></html>");

        JButton open = new JButton(tr("Open logs…"));
        open.addActionListener(_ -> onOpen.run());
        JButton example = new JButton(tr("Try the example"));
        example.setToolTipText(tr("Compare two bundled logs of a checkout service that started failing"));
        example.addActionListener(_ -> onExample.run());
        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(open);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(example);

        JPanel links = new JPanel();
        links.setOpaque(false);
        links.setLayout(new BoxLayout(links, BoxLayout.X_AXIS));
        boolean firstLink = true;
        for (java.util.Map.Entry<String, Runnable> e : more.entrySet()) {
            if (!firstLink) {
                JLabel dot = new JLabel("  \u00b7  ");
                dot.setForeground(palette.lineNumber());
                links.add(dot);
            }
            firstLink = false;
            links.add(link(e.getKey(), e.getValue()));
        }

        recentList.setOpaque(false);
        recentList.setLayout(new BoxLayout(recentList, BoxLayout.Y_AXIS));

        for (Component c : new Component[] {title, tagline, hint, buttons, links, recentList}) {
            ((javax.swing.JComponent) c).setAlignmentX(LEFT_ALIGNMENT);
        }
        card.add(title);
        card.add(Box.createVerticalStrut(4));
        card.add(tagline);
        card.add(Box.createVerticalStrut(14));
        card.add(hint);
        card.add(Box.createVerticalStrut(20));
        card.add(buttons);
        card.add(Box.createVerticalStrut(12));
        card.add(links);
        card.add(Box.createVerticalStrut(28));
        card.add(recentList);
        card.setBorder(BorderFactory.createEmptyBorder(36, 44, 36, 44));
        add(card);
    }

    public void setPalette(DiffPalette palette) {
        this.palette = palette;
        repaint();
    }

    /// Updates the recent comparisons list.
    public void setRecent(List<RecentPair> recent) {
        recentList.removeAll();
        if (!recent.isEmpty()) {
            JLabel header = new JLabel(tr("RECENT"));
            header.setFont(header.getFont().deriveFont(Font.BOLD, 11f));
            header.setForeground(palette.lineNumber());
            header.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
            recentList.add(header);
            for (RecentPair p : recent.subList(0, Math.min(6, recent.size()))) {
                JLabel link = link(p.label(), () -> onRecent.accept(p));
                link.setToolTipText("<html>" + io.github.logdelta.core.util.Text.escapeHtml(p.left()) + "<br>"
                        + io.github.logdelta.core.util.Text.escapeHtml(p.right()) + "</html>");
                link.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
                recentList.add(link);
            }
        }
        recentList.revalidate();
        recentList.repaint();
    }

    private JLabel link(String text, Runnable action) {
        JLabel link = new JLabel(text) {
            @Override
            public void updateUI() {
                super.updateUI();
                setForeground(palette.selectionBorder().darker());
            }
        };
        link.setForeground(palette.selectionBorder().darker());
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                action.run();
            }
        });
        return link;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(palette.background());
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // A subtle drop zone outline.
            int inset = 24;
            g.setColor(new Color(palette.divider().getRGB()));
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
                    new float[] {6f, 6f}, 0f));
            g.drawRoundRect(inset, inset, getWidth() - 2 * inset, getHeight() - 2 * inset, 16, 16);
        } finally {
            g.dispose();
        }
    }
}
