// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.sidebar;

import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.timing.PhaseChange;
import io.github.logdelta.core.timing.Timeline;
import io.github.logdelta.core.timing.TimingAnalysis;
import io.github.logdelta.core.util.LogText;
import io.github.logdelta.core.util.Text;
import io.github.logdelta.gui.theme.DiffPalette;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;
import java.util.function.IntConsumer;

import static io.github.logdelta.gui.i18n.I18n.tr;

/// Sidebar tab answering "where did the time go?": run durations and the phases between
/// matching lines that got notably slower or faster. Selecting a phase jumps to where it starts.
public final class TimingPanel extends JPanel {

    private final JLabel overview = new JLabel();
    private final JLabel coverage = new JLabel();
    private final DefaultListModel<Entry> phases = new DefaultListModel<>();
    private final JLabel caption = Section.caption(tr("Phases"));
    private DiffPalette palette = DiffPalette.LIGHT;

    private record Entry(PhaseChange phase, boolean slower) {
    }

    /// @param goToRow navigates to a diff row
    public TimingPanel(IntConsumer goToRow) {
        super(new BorderLayout());
        JPanel head = new JPanel(new GridLayout(0, 1, 0, 2));
        head.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        overview.setFont(overview.getFont().deriveFont(Font.BOLD));
        head.add(overview);
        head.add(coverage);

        JList<Entry> list = new JList<>(phases);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && list.getSelectedValue() != null) {
                goToRow.accept(list.getSelectedValue().phase().startRow());
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (list.getSelectedValue() != null) {
                    goToRow.accept(list.getSelectedValue().phase().startRow());
                }
            }
        });
        JPanel body = new JPanel(new BorderLayout());
        caption.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        body.add(caption, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        body.add(scroll, BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        show(null);
    }

    public void setPalette(DiffPalette palette) {
        this.palette = palette;
        repaint();
    }

    /// Shows the timing of `r`, or clears the panel if `null`.
    public void show(DiffResult r) {
        phases.clear();
        if (r == null) {
            overview.setText(tr("No comparison yet"));
            coverage.setText(" ");
            caption.setText(tr("Phases"));
            return;
        }
        TimingAnalysis t = r.timing();
        if (!t.available()) {
            overview.setText(tr("No timestamps found"));
            coverage.setText("<html>" + tr("Timing needs ISO, syslog, Apache or time-of-day timestamps at the "
                    + "start of lines.") + "</html>");
            caption.setText(tr("Phases"));
            return;
        }
        overview.setText(tr("Run took {0} → {1}", Timeline.format(t.left().duration()),
                Timeline.format(t.right().duration())));
        coverage.setText(tr("Timestamps on {0}% / {1}% of lines",
                String.format(Locale.ROOT, "%.0f", t.left().coverage() * 100),
                String.format(Locale.ROOT, "%.0f", t.right().coverage() * 100)));
        t.slower().forEach(p -> phases.addElement(new Entry(p, true)));
        t.faster().forEach(p -> phases.addElement(new Entry(p, false)));
        caption.setText(tr("Phases") + "  (" + tr("{0} slower, {1} faster", t.slower().size(),
                t.faster().size()) + ")");
    }

    /// Number of slower phases shown, for the tab title.
    public int slowerCount() {
        int n = 0;
        for (int i = 0; i < phases.size(); i++) {
            n += phases.get(i).slower() ? 1 : 0;
        }
        return n;
    }

    /// Two-line cell: factor and durations, then where the phase starts and ends.
    private final class Renderer extends JPanel implements ListCellRenderer<Entry> {
        private final JLabel head = new JLabel();
        private final JLabel span = new JLabel();

        Renderer() {
            super(new BorderLayout(0, 2));
            setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 8));
            add(head, BorderLayout.NORTH);
            add(span, BorderLayout.CENTER);
            span.setFont(span.getFont().deriveFont(Math.max(10f, span.getFont().getSize2D() - 1f)));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Entry> list, Entry e, int index,
                                                      boolean selected, boolean focus) {
            PhaseChange p = e.phase();
            String factor = p.factor() >= 10 ? String.format(Locale.ROOT, "%.0f×", p.factor())
                    : String.format(Locale.ROOT, "%.1f×", p.factor());
            String color = hex(e.slower() ? palette.slowPhase() : palette.addedMarker());
            head.setText("<html><b><font color='" + color + "'>" + (e.slower() ? factor + " slower" : "faster")
                    + "</font></b>&nbsp;&nbsp;" + Timeline.format(p.leftMillis()) + " → "
                    + Timeline.format(p.rightMillis()) + "</html>");
            span.setText("<html>" + Text.escapeHtml(Text.truncate(LogText.message(p.startText()).strip(), 70))
                    + "<br>→ " + Text.escapeHtml(Text.truncate(LogText.message(p.endText()).strip(), 70))
                    + "</html>");
            setToolTipText("<html>" + Text.escapeHtml(p.startText()) + "<br>→ " + Text.escapeHtml(p.endText())
                    + "</html>");
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            head.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            span.setForeground(selected ? list.getSelectionForeground() : palette.lineNumber());
            return this;
        }

        private static String hex(java.awt.Color c) {
            return String.format(Locale.ROOT, "#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
        }
    }
}
