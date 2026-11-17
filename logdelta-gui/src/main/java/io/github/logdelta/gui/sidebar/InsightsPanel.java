// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.sidebar;

import io.github.logdelta.core.analysis.FrequencyShift;
import io.github.logdelta.core.analysis.InsightItem;
import io.github.logdelta.core.analysis.Severity;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.util.LogText;
import io.github.logdelta.core.util.Text;
import io.github.logdelta.gui.theme.DiffPalette;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/// Sidebar tab answering "what went wrong?": problems that only appear in the candidate,
/// problems that disappeared, and messages whose frequency changed. Selecting an entry jumps to
/// it in the comparison.
public final class InsightsPanel extends JPanel {

    private final DefaultListModel<InsightItem> newModel = new DefaultListModel<>();
    private final DefaultListModel<InsightItem> resolvedModel = new DefaultListModel<>();
    private final ShiftModel shiftModel = new ShiftModel();
    private final JLabel newCaption = Section.caption("New problems");
    private final JLabel resolvedCaption = Section.caption("Resolved problems");
    private final JLabel shiftCaption = Section.caption("Frequency shifts");
    private DiffPalette palette = DiffPalette.LIGHT;

    /// @param goToRow navigates to a diff row
    public InsightsPanel(IntConsumer goToRow) {
        super(new BorderLayout());
        JList<InsightItem> newList = list(newModel, goToRow);
        JList<InsightItem> resolvedList = list(resolvedModel, goToRow);
        JTable shifts = new JTable(shiftModel) {
            @Override
            public String getToolTipText(MouseEvent e) {
                int r = rowAtPoint(e.getPoint());
                return r < 0 ? null : shiftModel.shifts.get(r).template();
            }
        };
        shifts.setFillsViewportHeight(true);
        shifts.setShowGrid(false);
        shifts.setRowHeight(shifts.getRowHeight() + 4);
        shifts.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        shifts.getColumnModel().getColumn(0).setPreferredWidth(240);
        for (int c = 1; c < 4; c++) {
            shifts.getColumnModel().getColumn(c).setPreferredWidth(44);
            shifts.getColumnModel().getColumn(c).setMaxWidth(70);
        }
        DefaultTableCellRenderer number = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r,
                                                           int c) {
                super.getTableCellRendererComponent(t, v, sel, focus, r, c);
                setHorizontalAlignment(RIGHT);
                if (c == 3 && !sel && v instanceof String s) {
                    setForeground(s.startsWith("+") ? palette.problem() : palette.addedMarker());
                } else if (!sel) {
                    setForeground(t.getForeground());
                }
                return this;
            }
        };
        for (int c = 1; c < 4; c++) {
            shifts.getColumnModel().getColumn(c).setCellRenderer(number);
        }
        shifts.getSelectionModel().addListSelectionListener(e -> {
            int r = shifts.getSelectedRow();
            if (!e.getValueIsAdjusting() && r >= 0 && shiftModel.shifts.get(r).firstRow() >= 0) {
                goToRow.accept(shiftModel.shifts.get(r).firstRow());
            }
        });

        JPanel top = block(newCaption, new JScrollPane(newList));
        JPanel middle = block(resolvedCaption, new JScrollPane(resolvedList));
        JPanel bottom = block(shiftCaption, new JScrollPane(shifts));

        JSplitPane lower = new JSplitPane(JSplitPane.VERTICAL_SPLIT, middle, bottom);
        lower.setResizeWeight(0.4);
        lower.setBorder(null);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, lower);
        split.setResizeWeight(0.5);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
    }

    public void setPalette(DiffPalette palette) {
        this.palette = palette;
        repaint();
    }

    /// Shows the insights of `r`, or clears the panel if `null`.
    public void show(DiffResult r) {
        newModel.clear();
        resolvedModel.clear();
        shiftModel.set(List.of());
        if (r != null) {
            r.insights().newProblems().forEach(newModel::addElement);
            r.insights().resolvedProblems().forEach(resolvedModel::addElement);
            shiftModel.set(r.insights().frequencyShifts());
        }
        newCaption.setText("NEW PROBLEMS  (" + newModel.size() + ")");
        resolvedCaption.setText("RESOLVED PROBLEMS  (" + resolvedModel.size() + ")");
        shiftCaption.setText("FREQUENCY SHIFTS  (" + shiftModel.getRowCount() + ")");
    }

    private static JPanel block(JLabel caption, JScrollPane body) {
        JPanel p = new JPanel(new BorderLayout());
        caption.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        p.add(caption, BorderLayout.NORTH);
        body.setBorder(BorderFactory.createEmptyBorder());
        p.add(body, BorderLayout.CENTER);
        p.setMinimumSize(new Dimension(100, 70));
        return p;
    }

    private JList<InsightItem> list(DefaultListModel<InsightItem> model, IntConsumer goToRow) {
        JList<InsightItem> list = new JList<>(model) {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return true;
            }
        };
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new ItemRenderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && list.getSelectedValue() != null) {
                goToRow.accept(list.getSelectedValue().row());
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                InsightItem item = list.getSelectedValue();
                if (item != null) {
                    goToRow.accept(item.row());
                }
            }
        });
        return list;
    }

    /// Two-line cell: severity badge and line number, then the message.
    private final class ItemRenderer extends JPanel implements ListCellRenderer<InsightItem> {
        private final Badge badge = new Badge();
        private final JLabel line = new JLabel();
        private final JLabel text = new JLabel();

        ItemRenderer() {
            super(new BorderLayout(8, 0));
            setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 8));
            JPanel head = new JPanel(new BorderLayout(8, 0));
            head.setOpaque(false);
            head.add(badge, BorderLayout.WEST);
            head.add(line, BorderLayout.CENTER);
            add(head, BorderLayout.NORTH);
            add(text, BorderLayout.CENTER);
            line.setFont(line.getFont().deriveFont(Math.max(10f, line.getFont().getSize2D() - 1f)));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends InsightItem> list, InsightItem item, int index,
                                                      boolean selected, boolean focus) {
            badge.severity = item.severity();
            line.setText("line " + item.lineNumber() + (item.lines() > 1 ? "  ·  +" + (item.lines() - 1)
                    + " following lines" : ""));
            text.setText(Text.truncate(LogText.message(item.text()), 200));
            setToolTipText(item.text());
            text.setFont(list.getFont());
            Color bg = selected ? list.getSelectionBackground() : list.getBackground();
            Color fg = selected ? list.getSelectionForeground() : list.getForeground();
            setBackground(bg);
            text.setForeground(fg);
            line.setForeground(selected ? fg : palette.lineNumber());
            return this;
        }
    }

    /// Coloured severity label.
    private final class Badge extends JLabel {
        Severity severity = Severity.NONE;

        Badge() {
            setFont(getFont().deriveFont(Font.BOLD, Math.max(9f, getFont().getSize2D() - 2f)));
            setPreferredSize(new Dimension(48, 16));
            setHorizontalAlignment(CENTER);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color c = severity.compareTo(Severity.ERROR) >= 0 ? palette.problem() : palette.modifiedMarker();
            g.setColor(c);
            g.fillRoundRect(0, 1, getWidth(), getHeight() - 2, 8, 8);
            g.setColor(Color.WHITE);
            g.setFont(getFont());
            String label = severity.label();
            int w = g.getFontMetrics().stringWidth(label);
            g.drawString(label, (getWidth() - w) / 2, (getHeight() + g.getFontMetrics().getAscent()) / 2 - 2);
            g.dispose();
        }
    }

    private static final class ShiftModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Message (normalized)", "Base", "Cand", "Δ"};
        private List<FrequencyShift> shifts = List.of();

        void set(List<FrequencyShift> s) {
            shifts = s;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return shifts.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLUMNS[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            FrequencyShift f = shifts.get(r);
            return switch (c) {
                case 0 -> LogText.message(f.template());
                case 1 -> String.valueOf(f.leftCount());
                case 2 -> String.valueOf(f.rightCount());
                default -> String.format(Locale.ROOT, "%+d", f.delta());
            };
        }
    }
}
