// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.command;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static io.github.logdelta.gui.i18n.I18n.tr;

/// The keyboard shortcut reference: every menu shortcut (read from the menu bar, so it is always
/// accurate) plus the keys that work in the comparison view, with a search field.
public final class ShortcutsDialog extends JDialog {

    private ShortcutsDialog(Window owner, JMenuBar menus, KeyStroke paletteKey) {
        super(owner, tr("Keyboard shortcuts"), ModalityType.MODELESS);
        List<String[]> rows = rows(menus, paletteKey);
        Model model = new Model(rows);
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setRowHeight(table.getRowHeight() + 4);
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getColumnModel().getColumn(2).setPreferredWidth(160);
        table.getAccessibleContext().setAccessibleName(tr("Keyboard shortcuts"));
        TableRowSorter<Model> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);

        JTextField search = new JTextField(24);
        search.putClientProperty("JTextField.placeholderText", tr("Search shortcuts"));
        search.getAccessibleContext().setAccessibleName(tr("Search shortcuts"));
        search.getDocument().addDocumentListener(new DocumentListener() {
            private void update() {
                String q = search.getText().strip();
                sorter.setRowFilter(q.isEmpty() ? null
                        : RowFilter.regexFilter("(?i)" + Pattern.quote(q)));
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                update();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                update();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                update();
            }
        });

        JPanel top = new JPanel(new BorderLayout(8, 0));
        JLabel label = new JLabel(tr("Search:"));
        label.setLabelFor(search);
        top.add(label, BorderLayout.WEST);
        top.add(search, BorderLayout.CENTER);
        top.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));

        JButton copy = new JButton(tr("Copy as Text"));
        copy.addActionListener(_ -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(asText(rows)), null));
        JButton close = new JButton(tr("Close"));
        close.addActionListener(_ -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(copy);
        buttons.add(close);

        JPanel root = new JPanel(new BorderLayout());
        root.add(top, BorderLayout.NORTH);
        root.add(new JScrollPane(table), BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().registerKeyboardAction(_ -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().setDefaultButton(close);
        setSize(new Dimension(680, 560));
        setLocationRelativeTo(owner);
    }

    /// Shows the reference.
    ///
    /// @param paletteKey shortcut of the command palette, listed first
    public static void show(Window owner, JMenuBar menus, KeyStroke paletteKey) {
        new ShortcutsDialog(owner, menus, paletteKey).setVisible(true);
    }

    /// Rows `{shortcut, command, where}`; package-private for tests.
    static List<String[]> rows(JMenuBar menus, KeyStroke paletteKey) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {MenuCommands.describe(paletteKey), tr("Command palette: run any command by name"),
            tr("Everywhere")});
        for (MenuCommands.Entry e : MenuCommands.collect(menus)) {
            if (e.accelerator() != null && !e.accelerator().equals(paletteKey)) {
                rows.add(new String[] {MenuCommands.describe(e.accelerator()), e.name(), String.join(" › ", e.path())});
            }
        }
        String view = tr("Comparison view");
        rows.add(new String[] {"↑ ↓  PgUp PgDn  Home End", tr("Move the selection"), view});
        rows.add(new String[] {"← →, " + tr("Shift+wheel"), tr("Scroll horizontally"), view});
        rows.add(new String[] {tr("Double-click a line number"), tr("Toggle a bookmark"), view});
        rows.add(new String[] {tr("Drag the middle divider"), tr("Resize the panes (double-click: equal widths)"), view});
        rows.add(new String[] {tr("Right-click a line"), tr("Copy, bookmark, ignore or test the line"), view});
        rows.add(new String[] {tr("Hover a masked value"), tr("See which rule masked it"), view});
        rows.add(new String[] {tr("Middle-click a tab"), tr("Close the tab"), tr("Tabs")});
        return rows;
    }

    static String asText(List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        int width = rows.stream().mapToInt(r -> r[0].length()).max().orElse(10);
        for (String[] r : rows) {
            sb.append(String.format(Locale.ROOT, "%-" + width + "s  %s  (%s)%n", r[0], r[1], r[2]));
        }
        return sb.toString();
    }

    private static final class Model extends AbstractTableModel {
        private final List<String[]> rows;

        Model(List<String[]> rows) {
            this.rows = rows;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int c) {
            return switch (c) {
                case 0 -> tr("Shortcut");
                case 1 -> tr("Command");
                default -> tr("Where");
            };
        }

        @Override
        public Object getValueAt(int r, int c) {
            return rows.get(r)[c];
        }
    }
}
