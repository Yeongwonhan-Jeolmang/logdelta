// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.view;

import io.github.logdelta.core.analysis.InsightItem;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.gui.model.DiffViewModel;
import io.github.logdelta.gui.model.ViewRow;
import io.github.logdelta.gui.theme.DiffPalette;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/// A narrow strip next to the scroll bar that shows where all changes are, so the shape of a
/// comparison is visible at a glance. Clicking or dragging jumps to that position.
///
/// Removed lines are drawn on the left half, added lines on the right half, modified and moved
/// lines across the full width. New problems get a mark in the problem colour. The visible
/// region is drawn as a translucent band.
public final class OverviewRuler extends JComponent {

    private static final int WIDTH = 14;
    private static final int INSET = 2;

    private final JScrollPane scrollPane;
    private DiffViewModel model;
    private DiffPalette palette = DiffPalette.LIGHT;

    /// @param scrollPane the scroll pane whose view this ruler summarises
    public OverviewRuler(JScrollPane scrollPane) {
        this.scrollPane = scrollPane;
        setPreferredSize(new Dimension(WIDTH, 100));
        setToolTipText("Overview of all changes — click to jump");
        scrollPane.getViewport().addChangeListener(_ -> repaint());
        MouseAdapter jump = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                scrollTo(e.getY());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                scrollTo(e.getY());
            }
        };
        addMouseListener(jump);
        addMouseMotionListener(jump);
    }

    public void setModel(DiffViewModel model) {
        this.model = model;
        repaint();
    }

    public void setPalette(DiffPalette palette) {
        this.palette = palette;
        repaint();
    }

    private int trackHeight() {
        return Math.max(1, getHeight() - 2 * INSET);
    }

    private void scrollTo(int y) {
        if (model == null || model.size() == 0) {
            return;
        }
        JViewport vp = scrollPane.getViewport();
        int viewHeight = vp.getView().getHeight();
        double fraction = Math.clamp((y - INSET) / (double) trackHeight(), 0.0, 1.0);
        int target = (int) (fraction * viewHeight) - vp.getHeight() / 2;
        target = Math.max(0, Math.min(target, viewHeight - vp.getHeight()));
        vp.setViewPosition(new java.awt.Point(0, target));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics;
        g.setColor(palette.gutter());
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(palette.divider());
        g.drawLine(0, 0, 0, getHeight());
        if (model == null || model.size() == 0) {
            return;
        }
        int n = model.size();
        int h = trackHeight();
        total = scrollPane.getViewport().getView() instanceof DiffCanvas c ? Math.max(1, c.getPreferredSize().height) : 0;
        int markH = Math.max(2, h / Math.max(1, n));
        int half = (getWidth() - 2) / 2;
        for (int v = 0; v < n; v++) {
            if (!(model.get(v) instanceof ViewRow.Line(int rowIndex))) {
                continue;
            }
            DiffRow row = model.result().rows().get(rowIndex);
            if (!row.isChange()) {
                continue;
            }
            int y = INSET + position(v, h, n);
            if (row.isMoved()) {
                fill(g, palette.movedMarker(), 2, y, getWidth() - 3, markH);
            } else {
                switch (row.kind()) {
                    case REMOVED -> fill(g, palette.removedMarker(), 2, y, half, markH);
                    case ADDED -> fill(g, palette.addedMarker(), 2 + half, y, half - 1, markH);
                    default -> fill(g, palette.modifiedMarker(), 2, y, getWidth() - 3, markH);
                }
            }
        }
        for (InsightItem p : model.result().insights().newProblems()) {
            int v = model.viewIndexOf(p.row());
            if (v >= 0) {
                int y = INSET + position(v, h, n);
                fill(g, palette.problem(), getWidth() - 4, y - 1, 3, Math.max(3, markH));
            }
        }
        // Visible region.
        JViewport vp = scrollPane.getViewport();
        int viewHeight = Math.max(1, vp.getView().getHeight());
        Rectangle visible = vp.getViewRect();
        int top = INSET + (int) ((long) visible.y * h / viewHeight);
        int height = Math.max(6, (int) ((long) visible.height * h / viewHeight));
        Color band = palette.selectionBorder();
        g.setColor(new Color(band.getRed(), band.getGreen(), band.getBlue(), 40));
        g.fillRect(1, top, getWidth() - 1, height);
        g.setColor(new Color(band.getRed(), band.getGreen(), band.getBlue(), 110));
        g.drawRect(1, top, getWidth() - 2, height - 1);
    }

    /// Ruler offset of view row `v`: proportional to its pixel position when the view is a
    /// [DiffCanvas] (rows can differ in height when wrapping), otherwise to its index.
    private int position(int v, int h, int n) {
        if (total > 0 && scrollPane.getViewport().getView() instanceof DiffCanvas canvas) {
            return (int) ((long) canvas.rowTop(v) * h / total);
        }
        return (int) ((long) v * h / n);
    }

    /// Height of the canvas during the current paint, or `0` if the view is not a canvas.
    private int total;

    private static void fill(Graphics2D g, Color c, int x, int y, int w, int h) {
        g.setColor(c);
        g.fillRect(x, y, w, h);
    }
}
