// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.sidebar;

import io.github.logdelta.core.analysis.Insights;
import io.github.logdelta.core.analysis.Severity;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.DiffStats;
import io.github.logdelta.core.util.Text;
import io.github.logdelta.gui.theme.DiffPalette;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.util.Locale;
import java.util.function.IntConsumer;

/// Sidebar tab with the headline numbers of a comparison.
public final class SummaryPanel extends JPanel {

    private final Content content = new Content();
    private final IntConsumer goToRow;
    private DiffPalette palette = DiffPalette.LIGHT;

    /// @param goToRow navigates to a diff row
    public SummaryPanel(IntConsumer goToRow) {
        super(new BorderLayout());
        this.goToRow = goToRow;
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        show(null);
    }

    public void setPalette(DiffPalette palette) {
        this.palette = palette;
    }

    /// Shows the summary of `r`, or a placeholder if `null`.
    public void show(DiffResult r) {
        content.removeAll();
        content.setBorder(BorderFactory.createEmptyBorder(4, 14, 14, 14));
        if (r == null) {
            JLabel empty = new JLabel("No comparison yet.");
            empty.setBorder(BorderFactory.createEmptyBorder(16, 0, 0, 0));
            content.add(empty);
            content.revalidate();
            content.repaint();
            return;
        }
        DiffStats s = r.stats();
        Insights in = r.insights();

        content.add(Section.caption("Similarity"));
        JLabel pct = new JLabel(String.format(Locale.ROOT, "%.1f%%", s.similarity() * 100));
        pct.setFont(pct.getFont().deriveFont(Font.BOLD, 30f));
        content.add(Section.compact(pct));
        JLabel verdict = new JLabel(s.identical() ? (s.expected() > 0 ? "Only normal variation"
                : "Identical after normalization")
                : String.format(Locale.ROOT, "%,d of %,d compared lines differ", s.changes(),
                s.unchanged() + s.changes()));
        content.add(Section.compact(verdict));
        content.add(Box.createVerticalStrut(8));
        content.add(Section.compact(new ProportionBar(s, palette)));

        content.add(Section.caption("Changes"));
        JPanel counts = grid();
        int row = 0;
        row = countRow(counts, row, palette.modifiedMarker(), "Modified", s.modified());
        row = countRow(counts, row, palette.removedMarker(), "Removed", s.removed() - s.moved());
        row = countRow(counts, row, palette.addedMarker(), "Added", s.added() - s.moved());
        row = countRow(counts, row, palette.movedMarker(), "Moved", s.moved());
        row = countRow(counts, row, palette.lineNumber(), "Unchanged", s.unchanged());
        if (!r.extraBaselines().isEmpty()) {
            countRow(counts, row, palette.lineNumber(), "Normal variation", s.expected());
        }
        content.add(Section.compact(counts));
        if (!r.extraBaselines().isEmpty()) {
            JLabel note = new JLabel("<html><div style='width:210px'>Also compared with " + r.extraBaselines().size() + " more baseline run"
                    + (r.extraBaselines().size() == 1 ? "" : "s") + "; differences they share are shown faded "
                    + "and marked \u2248.</div></html>");
            note.setForeground(palette.lineNumber());
            content.add(Box.createVerticalStrut(6));
            content.add(Section.compact(note));
        }

        content.add(Section.caption("Severity  (baseline → candidate)"));
        JPanel sev = grid();
        int errL = in.leftCount(Severity.ERROR) + in.leftCount(Severity.FATAL);
        int errR = in.rightCount(Severity.ERROR) + in.rightCount(Severity.FATAL);
        pairRow(sev, 0, "Errors", errL, errR);
        pairRow(sev, 1, "Warnings", in.leftCount(Severity.WARN), in.rightCount(Severity.WARN));
        pairRow(sev, 2, "New problems", 0, in.newProblems().size());
        content.add(Section.compact(sev));

        if (in.firstDivergenceRow() >= 0) {
            DiffRow first = r.rows().get(in.firstDivergenceRow());
            String where = (first.hasLeft() ? "line " + r.left(first).number() : "end") + " / "
                    + (first.hasRight() ? "line " + r.right(first).number() : "end");
            JButton jump = new JButton("Go to first difference (" + where + ")");
            jump.addActionListener(_ -> goToRow.accept(in.firstDivergenceRow()));
            content.add(Box.createVerticalStrut(12));
            content.add(Section.compact(jump));
        }

        content.add(Section.caption("Files"));
        JPanel files = grid();
        fileRows(files, 0, "Baseline", r.leftFile(), s.leftIgnored(), s.leftMasked());
        fileRows(files, 3, "Candidate", r.rightFile(), s.rightIgnored(), s.rightMasked());
        content.add(Section.compact(files));

        content.add(Section.caption("Comparison"));
        JPanel run = grid();
        kv(run, 0, "Algorithm", r.options().algorithm().displayName() + (r.options().minimal() ? " (minimal)" : ""));
        kv(run, 1, "Rules", r.options().rules().enabledRules().size() + " active");
        kv(run, 2, "Ignore patterns", String.valueOf(r.options().ignorePatterns().size()));
        kv(run, 3, "Time", r.elapsedMillis() + " ms");
        kv(run, 4, "Stack traces", r.options().multilineEntries() ? "grouped with their line" : "line by line");
        content.add(Section.compact(run));

        content.revalidate();
        content.repaint();
    }

    private static JPanel grid() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        return p;
    }

    private static GridBagConstraints at(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, 10);
        return c;
    }

    private static int countRow(JPanel p, int y, Color color, String label, int value) {
        p.add(new JLabel(new Dot(color)), at(0, y));
        GridBagConstraints c = at(1, y);
        c.weightx = 1;
        p.add(new JLabel(label), c);
        JLabel v = new JLabel(String.format(Locale.ROOT, "%,d", value));
        v.setFont(v.getFont().deriveFont(Font.BOLD));
        GridBagConstraints vc = at(2, y);
        vc.anchor = GridBagConstraints.EAST;
        p.add(v, vc);
        return y + 1;
    }

    private void pairRow(JPanel p, int y, String label, int left, int right) {
        GridBagConstraints c = at(0, y);
        c.weightx = 1;
        p.add(new JLabel(label), c);
        JLabel v = new JLabel(String.format(Locale.ROOT, "%,d → %,d", left, right));
        v.setFont(v.getFont().deriveFont(Font.BOLD));
        if (right > left) {
            v.setForeground(palette.problem());
        }
        GridBagConstraints vc = at(1, y);
        vc.anchor = GridBagConstraints.EAST;
        p.add(v, vc);
    }

    private static void kv(JPanel p, int y, String key, String value) {
        GridBagConstraints c = at(0, y);
        p.add(new JLabel(key), c);
        GridBagConstraints vc = at(1, y);
        vc.weightx = 1;
        JLabel v = new JLabel(value);
        p.add(v, vc);
    }

    private static void fileRows(JPanel p, int y, String role, LogFile f, int ignored, int masked) {
        JLabel name = new JLabel(role + ":  " + f.displayName());
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        name.setToolTipText(f.path() == null ? f.displayName() : f.path().toString());
        GridBagConstraints c = at(0, y);
        c.gridwidth = 2;
        p.add(name, c);
        kv(p, y + 1, "", String.format(Locale.ROOT, "%,d lines · %s · %s%s", f.lineCount(),
                Text.humanBytes(f.sizeBytes()), f.charset().name(), f.compressed() ? " · gzip" : ""));
        kv(p, y + 2, "", String.format(Locale.ROOT, "%,d masked · %,d ignored", masked, ignored));
    }

    /// A filled circle used as a legend swatch.
    private record Dot(Color color) implements javax.swing.Icon {
        @Override
        public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fill(new Ellipse2D.Double(x + 1, y + 1, 8, 8));
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 10;
        }

        @Override
        public int getIconHeight() {
            return 10;
        }
    }

    /// Horizontal bar showing the share of unchanged/modified/removed/added rows.
    private static final class ProportionBar extends JComponent {
        private final DiffStats s;
        private final DiffPalette p;

        ProportionBar(DiffStats s, DiffPalette p) {
            this.s = s;
            this.p = p;
            setPreferredSize(new Dimension(200, 8));
            setToolTipText("Share of unchanged, modified, removed, moved and added rows");
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int total = Math.max(1, s.unchanged() + s.changes());
            int w = getWidth();
            int h = getHeight();
            g.setClip(new java.awt.geom.RoundRectangle2D.Double(0, 0, w, h, h, h));
            int[] values = {s.unchanged(), s.modified(), s.removed() - s.moved(), s.moved() * 2,
                s.added() - s.moved()};
            Color[] colors = {p.divider(), p.modifiedMarker(), p.removedMarker(), p.movedMarker(), p.addedMarker()};
            int x = 0;
            for (int i = 0; i < values.length; i++) {
                int segment = (int) Math.round((double) values[i] * w / total);
                if (i == values.length - 1) {
                    segment = w - x;
                }
                g.setColor(colors[i]);
                g.fillRect(x, 0, segment, h);
                x += segment;
            }
            g.dispose();
        }
    }

    /// Panel that tracks the viewport width so text wraps instead of scrolling sideways.
    private static final class Content extends JPanel implements Scrollable {
        Content() {
            setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return r.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
