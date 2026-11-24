// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.view;

import io.github.logdelta.core.analysis.ProblemTimeline;
import io.github.logdelta.core.timing.Timeline;
import io.github.logdelta.gui.theme.DiffPalette;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;
import java.util.function.IntConsumer;

import static io.github.logdelta.gui.i18n.I18n.tr;

/// A small chart of warnings and errors over time: one strip for the baseline, one for the
/// candidate, on the same scale. Errors are drawn in the problem colour and warnings in the
/// modified colour; hovering a bar shows its counts and clicking it jumps to its first problem.
public final class ProblemTimelineChart extends JComponent {

    private static final int LABEL_WIDTH = 74;
    private static final int STRIP_HEIGHT = 30;
    private static final int GAP = 6;
    private static final int AXIS = 16;

    private ProblemTimeline timeline;
    private DiffPalette palette = DiffPalette.LIGHT;

    /// @param goToRow navigates to a diff row
    public ProblemTimelineChart(IntConsumer goToRow) {
        setOpaque(false);
        setToolTipText("");
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = rowAt(e.getX(), e.getY());
                if (row >= 0) {
                    goToRow.accept(row);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                setCursor(rowAt(e.getX(), e.getY()) >= 0 ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /// Shows a timeline, or nothing for `null`.
    public void setTimeline(ProblemTimeline t) {
        this.timeline = t;
        getAccessibleContext().setAccessibleDescription(describe());
        revalidate();
        repaint();
    }

    public void setPalette(DiffPalette palette) {
        this.palette = palette;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(240, 2 * STRIP_HEIGHT + GAP + AXIS);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /// A sentence for screen readers and the tooltip of the empty chart.
    String describe() {
        if (timeline == null) {
            return "";
        }
        ProblemTimeline.Side l = timeline.left();
        ProblemTimeline.Side r = timeline.right();
        return tr("Baseline: {0} errors and {1} warnings. Candidate: {2} errors and {3} warnings.",
                l.totalErrors(), l.totalWarnings(), r.totalErrors(), r.totalWarnings());
    }

    private int bins() {
        return timeline == null ? 0 : timeline.bins();
    }

    private double binWidth() {
        return Math.max(1, (getWidth() - LABEL_WIDTH - 2)) / (double) Math.max(1, bins());
    }

    private int binAt(int x) {
        if (timeline == null || x < LABEL_WIDTH) {
            return -1;
        }
        int b = (int) ((x - LABEL_WIDTH) / binWidth());
        return b >= 0 && b < bins() ? b : -1;
    }

    private ProblemTimeline.Side sideAt(int y) {
        if (timeline == null) {
            return null;
        }
        if (y >= 0 && y < STRIP_HEIGHT) {
            return timeline.left();
        }
        if (y >= STRIP_HEIGHT + GAP && y < 2 * STRIP_HEIGHT + GAP) {
            return timeline.right();
        }
        return null;
    }

    private int rowAt(int x, int y) {
        int b = binAt(x);
        ProblemTimeline.Side s = sideAt(y);
        return b < 0 || s == null ? -1 : s.firstRows()[b];
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        int b = binAt(e.getX());
        ProblemTimeline.Side s = sideAt(e.getY());
        if (b < 0 || s == null) {
            return describe();
        }
        String when;
        if (timeline.byTime()) {
            long from = timeline.spanMillis() * b / bins();
            long to = timeline.spanMillis() * (b + 1) / bins();
            when = Timeline.formatOffset(from) + " – " + Timeline.formatOffset(to);
        } else {
            when = String.format(Locale.ROOT, "%d%% – %d%%", b * 100 / bins(), (b + 1) * 100 / bins());
        }
        String side = s == timeline.left() ? tr("Baseline") : tr("Candidate");
        return "<html><b>" + side + "</b> " + when + "<br>" + tr("{0} errors, {1} warnings", s.errors()[b],
                s.warnings()[b]) + (s.firstRows()[b] >= 0 ? "<br><i>" + tr("Click to go to the first one") + "</i>"
                : "") + "</html>";
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (timeline == null) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font small = getFont().deriveFont(Math.max(9f, getFont().getSize2D() - 2f));
        g.setFont(small);
        int peak = Math.max(1, timeline.peak());
        strip(g, tr("Baseline"), timeline.left(), 0, peak);
        strip(g, tr("Candidate"), timeline.right(), STRIP_HEIGHT + GAP, peak);
        g.setColor(palette.lineNumber());
        int axisY = 2 * STRIP_HEIGHT + GAP + g.getFontMetrics().getAscent() + 2;
        String start = timeline.byTime() ? "0" : "0%";
        String end = timeline.byTime() ? Timeline.format(timeline.spanMillis()) : "100%";
        g.drawString(start, LABEL_WIDTH, axisY);
        g.drawString(end, getWidth() - g.getFontMetrics().stringWidth(end) - 2, axisY);
        g.dispose();
    }

    private void strip(Graphics2D g, String label, ProblemTimeline.Side side, int y, int peak) {
        g.setColor(palette.lineNumber());
        g.drawString(label, 0, y + (STRIP_HEIGHT + g.getFontMetrics().getAscent()) / 2 - 1);
        g.setColor(palette.gutter());
        g.fillRect(LABEL_WIDTH, y, getWidth() - LABEL_WIDTH - 1, STRIP_HEIGHT);
        g.setColor(palette.divider());
        g.drawRect(LABEL_WIDTH, y, getWidth() - LABEL_WIDTH - 2, STRIP_HEIGHT - 1);
        double w = binWidth();
        int inner = STRIP_HEIGHT - 2;
        for (int b = 0; b < side.warnings().length; b++) {
            int errors = side.errors()[b];
            int warnings = side.warnings()[b];
            if (errors + warnings == 0) {
                continue;
            }
            int x = LABEL_WIDTH + 1 + (int) Math.round(b * w);
            int bw = Math.max(1, (int) Math.round((b + 1) * w) - (int) Math.round(b * w) - 1);
            int eh = (int) Math.ceil((double) errors * inner / peak);
            int wh = (int) Math.ceil((double) warnings * inner / peak);
            int bottom = y + STRIP_HEIGHT - 1;
            if (warnings > 0) {
                g.setColor(palette.modifiedMarker());
                g.fillRect(x, bottom - eh - wh, bw, wh);
            }
            if (errors > 0) {
                g.setColor(palette.problem());
                g.fillRect(x, bottom - eh, bw, eh);
            }
        }
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.CANVAS;
                }
            };
            accessibleContext.setAccessibleName(tr("Warnings and errors over time"));
        }
        return accessibleContext;
    }
}
