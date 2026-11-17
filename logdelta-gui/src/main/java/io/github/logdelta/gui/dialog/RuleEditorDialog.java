// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.dialog;

import io.github.logdelta.core.normalize.BuiltinRules;
import io.github.logdelta.core.normalize.InvalidRuleException;
import io.github.logdelta.core.normalize.Mask;
import io.github.logdelta.core.normalize.NormalizedText;
import io.github.logdelta.core.normalize.Normalizer;
import io.github.logdelta.core.normalize.Rule;
import io.github.logdelta.core.normalize.RuleCategory;
import io.github.logdelta.core.normalize.RuleSet;
import io.github.logdelta.gui.theme.DiffPalette;
import io.github.logdelta.gui.theme.Theme;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/// Modal editor for the rule list: add, duplicate, delete, reorder and edit rules, with a live
/// tester that shows how sample lines are normalized by the rules being edited.
///
/// Edits are made on a working copy; [#showDialog()] returns the new rule set on OK and
/// `null` on cancel.
public final class RuleEditorDialog extends JDialog {

    private static final String DEFAULT_SAMPLE = String.join("\n",
            "2026-11-12 08:49:03.117 INFO  [pool-1-thread-4] Order ORD-123456 placed in 42ms by 10.0.3.19:53143",
            "2026-11-12 08:49:04.902 WARN  [http-nio-8080-exec-7] requestId=3f2b8c1e-9a4d-4e7b-8c2a-1d5e6f7a8b9c retry",
            "Nov 12 08:49:05 web-1 sshd[1234]: Accepted publickey for deploy from 192.168.1.20 port 51522");

    private final List<Rule> rules;
    private final TableModel tableModel = new TableModel();
    private final JTable table = new JTable(tableModel);

    private final JTextField idField = new JTextField();
    private final JTextField nameField = new JTextField();
    private final JComboBox<RuleCategory> categoryBox = new JComboBox<>(RuleCategory.values());
    private final JTextField patternField = new JTextField();
    private final JTextField replacementField = new JTextField();
    private final JCheckBox caseBox = new JCheckBox("Ignore case");
    private final JCheckBox enabledBox = new JCheckBox("Enabled");
    private final JTextField descriptionField = new JTextField();
    private final JLabel status = new JLabel(" ");
    private final JButton restore = new JButton("Restore default");
    private final JTextArea sample = new JTextArea(4, 60);
    private final JTextPane output = new JTextPane();
    private final DiffPalette palette;

    private boolean loading;
    private RuleSet result;

    /// @param owner      parent window
    /// @param ruleSet    rules to edit
    /// @param sampleText initial tester text, or `null` for a default sample
    /// @param palette    colours used to highlight masks in the tester
    public RuleEditorDialog(Window owner, RuleSet ruleSet, String sampleText, DiffPalette palette) {
        super(owner, "Normalization rules", ModalityType.APPLICATION_MODAL);
        this.rules = new ArrayList<>(ruleSet.rules());
        this.palette = palette;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JSplitPane top = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildList(), buildForm());
        top.setResizeWeight(0.55);
        top.setBorder(null);
        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, buildTester());
        main.setResizeWeight(0.65);
        main.setBorder(null);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(_ -> dispose());
        JButton ok = new JButton("OK");
        ok.addActionListener(_ -> {
            result = RuleSet.of(rules);
            dispose();
        });
        buttons.add(cancel);
        buttons.add(ok);
        getRootPane().setDefaultButton(ok);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        root.add(main, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);

        sample.setText(sampleText == null || sampleText.isBlank() ? DEFAULT_SAMPLE : sampleText);
        sample.getDocument().addDocumentListener(onChange(this::updateTester));
        setSize(1060, 720);
        setLocationRelativeTo(owner);
        if (!rules.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        }
        updateTester();
    }

    /// Shows the dialog and returns the edited rules, or `null` if cancelled.
    public RuleSet showDialog() {
        setVisible(true);
        return result;
    }

    // --- list ----------------------------------------------------------------------------------

    private JPanel buildList() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(false);
        table.setRowHeight(table.getRowHeight() + 4);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setMaxWidth(30);
        table.getColumnModel().getColumn(1).setPreferredWidth(170);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.getColumnModel().getColumn(3).setPreferredWidth(110);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadSelected();
            }
        });

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        actions.add(button("New", "Add a custom rule", this::addRule));
        actions.add(button("Duplicate", "Copy the selected rule", this::duplicateRule));
        actions.add(button("Delete", "Remove the selected rule", this::deleteRule));
        actions.add(button("↑", "Move up: rules run from top to bottom", () -> move(-1)));
        actions.add(button("↓", "Move down", () -> move(1)));

        JLabel hint = new JLabel("Rules run top to bottom; later rules never match text an earlier rule masked.");
        hint.setFont(hint.getFont().deriveFont(Math.max(10f, hint.getFont().getSize2D() - 1f)));
        hint.setBorder(BorderFactory.createEmptyBorder(0, 4, 6, 4));

        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(actions, BorderLayout.NORTH);
        south.add(hint, BorderLayout.SOUTH);
        p.add(south, BorderLayout.SOUTH);
        p.setPreferredSize(new Dimension(520, 380));
        return p;
    }

    private static JButton button(String text, String tip, Runnable action) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.addActionListener(_ -> action.run());
        return b;
    }

    private int selectedIndex() {
        return table.getSelectedRow();
    }

    private void select(int index) {
        if (index >= 0 && index < rules.size()) {
            table.setRowSelectionInterval(index, index);
            table.scrollRectToVisible(table.getCellRect(index, 0, true));
        }
    }

    private void addRule() {
        String id = uniqueId("custom");
        Rule r = Rule.builder(id).name("New rule").pattern("CHANGE-ME-\\d+").replacement("<MASKED>").build();
        int at = Math.max(0, selectedIndex());
        rules.add(at, r);
        tableModel.fireTableDataChanged();
        select(at);
        patternField.requestFocusInWindow();
        patternField.selectAll();
        updateTester();
    }

    private void duplicateRule() {
        int i = selectedIndex();
        if (i < 0) {
            return;
        }
        Rule src = rules.get(i);
        Rule copy = src.toBuilder().id(uniqueId(src.id())).name(src.name() + " (copy)").builtin(false).build();
        rules.add(i + 1, copy);
        tableModel.fireTableDataChanged();
        select(i + 1);
        updateTester();
    }

    private void deleteRule() {
        int i = selectedIndex();
        if (i < 0) {
            return;
        }
        rules.remove(i);
        tableModel.fireTableDataChanged();
        select(Math.min(i, rules.size() - 1));
        updateTester();
    }

    private void move(int delta) {
        int i = selectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= rules.size()) {
            return;
        }
        Rule r = rules.remove(i);
        rules.add(j, r);
        tableModel.fireTableDataChanged();
        select(j);
        updateTester();
    }

    private String uniqueId(String base) {
        String b = base.replaceAll("-\\d+$", "");
        for (int n = 1; ; n++) {
            String candidate = b + "-" + n;
            if (rules.stream().noneMatch(r -> r.id().equals(candidate))) {
                return candidate;
            }
        }
    }

    // --- form ----------------------------------------------------------------------------------

    private JPanel buildForm() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 4));
        Font mono = Theme.monoFont(13f);
        patternField.setFont(mono);
        replacementField.setFont(mono);
        idField.setFont(mono);
        int y = 0;
        y = row(p, y, "Id", idField, "Unique identifier, used in profiles and on the command line");
        y = row(p, y, "Name", nameField, null);
        y = row(p, y, "Category", categoryBox, null);
        y = row(p, y, "Pattern", patternField, "Java regular expression");
        y = row(p, y, "Replace with", replacementField, "Use $1 or ${name} to keep captured text");
        y = row(p, y, "Description", descriptionField, null);
        JPanel checks = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        checks.add(enabledBox);
        checks.add(javax.swing.Box.createHorizontalStrut(16));
        checks.add(caseBox);
        y = row(p, y, "", checks, null);

        restore.setToolTipText("Reset this built-in rule to the shipped definition");
        restore.addActionListener(_ -> restoreDefault());
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(restore, BorderLayout.EAST);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = y;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(10, 0, 0, 0);
        p.add(bottom, c);
        c.gridy = y + 1;
        c.weighty = 1;
        p.add(new JPanel(), c);

        DocumentListener changed = onChange(this::applyForm);
        for (JTextField f : List.of(idField, nameField, patternField, replacementField, descriptionField)) {
            f.getDocument().addDocumentListener(changed);
        }
        categoryBox.addActionListener(_ -> applyForm());
        caseBox.addActionListener(_ -> applyForm());
        enabledBox.addActionListener(_ -> applyForm());
        return p;
    }

    private static int row(JPanel p, int y, String label, JComponent field, String tip) {
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.gridy = y;
        l.anchor = GridBagConstraints.WEST;
        l.insets = new Insets(4, 0, 4, 10);
        p.add(new JLabel(label), l);
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.gridy = y;
        f.weightx = 1;
        f.fill = GridBagConstraints.HORIZONTAL;
        f.insets = new Insets(4, 0, 4, 0);
        if (tip != null) {
            field.setToolTipText(tip);
        }
        p.add(field, f);
        return y + 1;
    }

    private void loadSelected() {
        int i = selectedIndex();
        loading = true;
        try {
            boolean has = i >= 0;
            for (JComponent c : List.of(idField, nameField, categoryBox, patternField, replacementField,
                    descriptionField, caseBox, enabledBox)) {
                c.setEnabled(has);
            }
            if (!has) {
                restore.setEnabled(false);
                return;
            }
            Rule r = rules.get(i);
            idField.setText(r.id());
            idField.setEditable(!r.builtin());
            nameField.setText(r.name());
            categoryBox.setSelectedItem(r.category());
            patternField.setText(r.regex());
            patternField.setCaretPosition(0);
            replacementField.setText(r.replacement());
            descriptionField.setText(r.description());
            caseBox.setSelected(r.caseInsensitive());
            enabledBox.setSelected(r.enabled());
            updateRestore(r);
            setStatus(r.builtin() ? "Built-in rule" : "Custom rule", false);
        } finally {
            loading = false;
        }
    }

    private void applyForm() {
        int i = selectedIndex();
        if (loading || i < 0) {
            return;
        }
        Rule old = rules.get(i);
        String id = idField.getText().strip();
        for (int k = 0; k < rules.size(); k++) {
            if (k != i && rules.get(k).id().equals(id)) {
                setStatus("Another rule already uses the id '" + id + "'", true);
                return;
            }
        }
        try {
            Rule r = Rule.builder(id).name(nameField.getText()).category((RuleCategory) categoryBox.getSelectedItem())
                    .pattern(patternField.getText()).replacement(replacementField.getText())
                    .description(descriptionField.getText()).caseInsensitive(caseBox.isSelected())
                    .enabled(enabledBox.isSelected()).builtin(old.builtin()).build();
            rules.set(i, r);
            tableModel.fireTableRowsUpdated(i, i);
            updateRestore(r);
            setStatus(r.builtin() ? "Built-in rule" : "Custom rule", false);
            updateTester();
        } catch (InvalidRuleException ex) {
            setStatus(ex.getMessage(), true);
        }
    }

    private void restoreDefault() {
        int i = selectedIndex();
        if (i < 0) {
            return;
        }
        Rule builtin = BuiltinRules.find(rules.get(i).id());
        if (builtin != null) {
            rules.set(i, builtin.withEnabled(rules.get(i).enabled()));
            tableModel.fireTableRowsUpdated(i, i);
            loadSelected();
            updateTester();
        }
    }

    private void updateRestore(Rule r) {
        Rule builtin = r.builtin() ? BuiltinRules.find(r.id()) : null;
        restore.setVisible(builtin != null);
        restore.setEnabled(builtin != null && !builtin.withEnabled(r.enabled()).equals(r));
    }

    private void setStatus(String text, boolean error) {
        status.setText(text);
        Color red = UIManager.getColor("nimbusRed");
        status.setForeground(error ? (red == null ? Color.RED : red) : UIManager.getColor("Label.foreground"));
    }

    // --- tester --------------------------------------------------------------------------------

    private JPanel buildTester() {
        sample.setFont(Theme.monoFont(13f));
        output.setFont(Theme.monoFont(13f));
        output.setEditable(false);
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        JLabel in = new JLabel("Test lines (paste lines from your logs)");
        JLabel out = new JLabel("Normalized with the rules above — masked text is highlighted");
        JPanel inPanel = new JPanel(new BorderLayout(0, 4));
        inPanel.add(in, BorderLayout.NORTH);
        inPanel.add(new JScrollPane(sample), BorderLayout.CENTER);
        JPanel outPanel = new JPanel(new BorderLayout(0, 4));
        outPanel.add(out, BorderLayout.NORTH);
        outPanel.add(new JScrollPane(output), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, inPanel, outPanel);
        split.setResizeWeight(0.5);
        split.setBorder(null);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private void updateTester() {
        Normalizer normalizer;
        try {
            normalizer = new Normalizer(RuleSet.of(rules), false, false);
        } catch (InvalidRuleException _) {
            return;
        }
        StyledDocument doc = output.getStyledDocument();
        SimpleAttributeSet plain = new SimpleAttributeSet();
        SimpleAttributeSet masked = new SimpleAttributeSet();
        StyleConstants.setBackground(masked, palette.selection());
        StyleConstants.setForeground(masked, palette.selectionBorder().darker());
        StyleConstants.setBold(masked, true);
        SimpleAttributeSet info = new SimpleAttributeSet();
        StyleConstants.setForeground(info, palette.lineNumber());
        StyleConstants.setItalic(info, true);
        try {
            doc.remove(0, doc.getLength());
            for (String line : sample.getText().split("\\R", -1)) {
                if (line.isEmpty()) {
                    continue;
                }
                NormalizedText n = normalizer.normalize(line);
                int pos = 0;
                for (Mask m : n.masks()) {
                    doc.insertString(doc.getLength(), line.substring(pos, m.start()), plain);
                    doc.insertString(doc.getLength(), m.replacement(), masked);
                    pos = m.end();
                }
                doc.insertString(doc.getLength(), line.substring(pos) + "\n", plain);
                StringBuilder used = new StringBuilder();
                for (Mask m : n.masks()) {
                    if (used.indexOf(m.ruleId()) < 0) {
                        used.append(used.length() == 0 ? "" : ", ").append(m.ruleId());
                    }
                }
                doc.insertString(doc.getLength(), "    " + (used.length() == 0 ? "no rule matched"
                        : "rules: " + used) + "\n", info);
            }
            output.setCaretPosition(0);
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DocumentListener onChange(Runnable r) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                r.run();
            }
        };
    }

    private final class TableModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rules.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int c) {
            return switch (c) {
                case 0 -> "";
                case 1 -> "Name";
                case 2 -> "Category";
                default -> "Replaced by";
            };
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 0;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Rule rule = rules.get(r);
            return switch (c) {
                case 0 -> rule.enabled();
                case 1 -> rule.name() + (rule.builtin() ? "" : "  •");
                case 2 -> rule.category().displayName();
                default -> rule.replacement();
            };
        }

        @Override
        public void setValueAt(Object value, int r, int c) {
            rules.set(r, rules.get(r).withEnabled((Boolean) value));
            fireTableRowsUpdated(r, r);
            if (r == selectedIndex()) {
                loadSelected();
            }
            updateTester();
        }
    }
}
