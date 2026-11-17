// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.sidebar;

import io.github.logdelta.core.diff.Algorithm;
import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.normalize.InvalidRuleException;
import io.github.logdelta.core.normalize.LineFilter;
import io.github.logdelta.core.normalize.Rule;
import io.github.logdelta.core.normalize.RuleSet;
import io.github.logdelta.core.suggest.RuleSuggestion;
import io.github.logdelta.core.util.Text;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/// Sidebar tab for the options that most often need adjusting while looking at a comparison:
/// comparison flags, which rules are active, and which lines to ignore. Every change is applied
/// immediately (the controller debounces re-comparison).
public final class RulesPanel extends JPanel {

    private final Consumer<DiffOptions> onChange;
    private final JCheckBox ignoreCase = new JCheckBox("Ignore case");
    private final JCheckBox ignoreWhitespace = new JCheckBox("Ignore whitespace");
    private final JCheckBox detectMoves = new JCheckBox("Detect moved lines");
    private final JCheckBox multiline = new JCheckBox("Keep stack traces with their line");
    private final JPanel suggestionList = Section.column();
    private final JPanel suggestions = new JPanel(new BorderLayout());
    private final JComboBox<Algorithm> algorithm = new JComboBox<>(Algorithm.values());
    private final RuleTableModel rulesModel = new RuleTableModel();
    private final DefaultListModel<String> ignoreModel = new DefaultListModel<>();
    private final JList<String> ignoreList = new JList<>(ignoreModel);
    private DiffOptions options = DiffOptions.defaults();
    private boolean updating;

    /// @param onChange   receives the new options after every edit
    /// @param editRules  opens the full rule editor
    public RulesPanel(Consumer<DiffOptions> onChange, Runnable editRules) {
        super(new BorderLayout());
        this.onChange = onChange;

        // Options.
        JPanel flags = Section.column();
        flags.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        flags.add(Section.caption("Comparison"));
        for (JCheckBox b : List.of(ignoreCase, ignoreWhitespace, detectMoves, multiline)) {
            b.setOpaque(false);
            b.addActionListener(_ -> fire());
            flags.add(Section.compact(b));
        }
        ignoreCase.setToolTipText("Treat upper- and lower-case letters as equal");
        ignoreWhitespace.setToolTipText("Treat runs of spaces and tabs as one space; ignore leading/trailing space");
        detectMoves.setToolTipText("Show identical lines that changed position as moved instead of removed+added");
        multiline.setToolTipText("<html>Compare a log line and its continuation lines (stack traces, wrapped<br>"
                + "messages) as one entry, so a trace never gets matched to the wrong error</html>");
        JPanel algo = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        algo.setOpaque(false);
        algo.add(new JLabel("Algorithm  "));
        algo.add(algorithm);
        algorithm.setToolTipText("<html>Myers: shortest edit script (default)<br>"
                + "Patience: anchors on unique lines; reads better when sections are reordered</html>");
        algorithm.addActionListener(_ -> fire());
        flags.add(Section.compact(algo));

        // Rules.
        JTable table = new JTable(rulesModel) {
            @Override
            public String getToolTipText(MouseEvent e) {
                int r = rowAtPoint(e.getPoint());
                if (r < 0) {
                    return null;
                }
                Rule rule = rulesModel.rules.get(r);
                return "<html><b>" + Text.escapeHtml(rule.name()) + "</b> <i>(" + rule.id() + ")</i><br>"
                        + (rule.description().isEmpty() ? "" : Text.escapeHtml(rule.description()) + "<br>")
                        + "<code>" + Text.escapeHtml(rule.regex()) + "</code></html>";
            }
        };
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(false);
        table.setRowHeight(table.getRowHeight() + 4);
        table.getColumnModel().getColumn(0).setMaxWidth(30);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.getTableHeader().setReorderingAllowed(false);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(240, 240));

        JPanel ruleButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        JButton edit = new JButton("Edit rules…");
        edit.setToolTipText("Add, edit, reorder and test rules (Ctrl+R)");
        edit.addActionListener(_ -> editRules.run());
        JButton all = new JButton("All on");
        all.addActionListener(_ -> {
            options = options.toBuilder().rules(options.rules().withAllEnabled(true)).build();
            refresh();
            onChange.accept(options);
        });
        JButton defaults = new JButton("Defaults");
        defaults.setToolTipText("Restore the built-in rules and their default states (custom rules are kept)");
        defaults.addActionListener(_ -> restoreDefaults());
        ruleButtons.add(edit);
        ruleButtons.add(all);
        ruleButtons.add(defaults);

        JPanel rules = new JPanel(new BorderLayout());
        JLabel rulesCaption = Section.caption("Normalization rules");
        rulesCaption.setBorder(BorderFactory.createEmptyBorder(14, 12, 6, 12));
        rules.add(rulesCaption, BorderLayout.NORTH);
        rules.add(tableScroll, BorderLayout.CENTER);
        rules.add(ruleButtons, BorderLayout.SOUTH);

        // Ignore patterns.
        ignoreList.setVisibleRowCount(4);
        ignoreList.setToolTipText("Lines matching any of these regular expressions are left out of the comparison");
        JPanel ignoreButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        JButton add = new JButton("Add…");
        add.addActionListener(_ -> addIgnore(null));
        JButton remove = new JButton("Remove");
        remove.addActionListener(_ -> removeIgnore());
        ignoreButtons.add(add);
        ignoreButtons.add(remove);
        JPanel ignore = new JPanel(new BorderLayout());
        JLabel ignoreCaption = Section.caption("Ignored lines");
        ignoreCaption.setBorder(BorderFactory.createEmptyBorder(8, 12, 6, 12));
        ignore.add(ignoreCaption, BorderLayout.NORTH);
        ignore.add(new JScrollPane(ignoreList), BorderLayout.CENTER);
        ignore.add(ignoreButtons, BorderLayout.SOUTH);

        // Suggestions (hidden unless the last comparison produced some).
        JLabel suggestionCaption = Section.caption("Suggested rules");
        suggestionCaption.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        suggestions.add(suggestionCaption, BorderLayout.NORTH);
        suggestionList.setBorder(BorderFactory.createEmptyBorder(0, 12, 4, 12));
        suggestions.add(suggestionList, BorderLayout.CENTER);
        suggestions.setVisible(false);
        suggestions.setAlignmentX(LEFT_ALIGNMENT);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(suggestions);
        top.add(flags);
        top.add(Box.createVerticalStrut(4));

        add(top, BorderLayout.NORTH);
        add(rules, BorderLayout.CENTER);
        add(ignore, BorderLayout.SOUTH);
    }

    /// Shows rules that would hide run-specific differences of the last comparison, each with an
    /// Apply button. An empty list hides the section.
    public void setSuggestions(List<RuleSuggestion> list) {
        suggestionList.removeAll();
        for (RuleSuggestion sug : list) {
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);
            row.setAlignmentX(LEFT_ALIGNMENT);
            JLabel text = new JLabel("<html><b>" + sug.resolves() + "</b> lines " + Text.escapeHtml(sug.summary())
                    + "</html>");
            text.setToolTipText("<html>Masks <code>" + Text.escapeHtml(sug.rule().regex()) + "</code> as <code>"
                    + Text.escapeHtml(sug.rule().replacement()) + "</code><br>Example: <code>"
                    + Text.escapeHtml(Text.truncate(sug.exampleRight(), 100)) + "</code></html>");
            JButton apply = new JButton("Apply");
            apply.setToolTipText("Add this rule; the comparison updates immediately");
            apply.addActionListener(_ -> apply(sug));
            row.add(text, BorderLayout.CENTER);
            row.add(apply, BorderLayout.EAST);
            row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 4));
            suggestionList.add(row);
        }
        suggestions.setVisible(!list.isEmpty());
        revalidate();
        repaint();
    }

    private void apply(RuleSuggestion sug) {
        Rule rule = sug.rule();
        String id = rule.id();
        for (int n = 2; options.rules().find(id) != null; n++) {
            id = rule.id() + "-" + n;
        }
        Rule unique = id.equals(rule.id()) ? rule : rule.toBuilder().id(id).build();
        options = options.toBuilder().rules(options.rules().withAt(0, unique)).build();
        refresh();
        onChange.accept(options);
    }

    /// Displays `o` without notifying the listener.
    public void setOptions(DiffOptions o) {
        this.options = o;
        refresh();
    }

    /// Asks for an ignore pattern, pre-filled with `suggestion`, and adds it.
    ///
    /// @return whether a pattern was added
    public boolean addIgnore(String suggestion) {
        Object input = JOptionPane.showInputDialog(this,
                "Ignore lines matching this regular expression:", "Ignore lines",
                JOptionPane.PLAIN_MESSAGE, null, null, suggestion == null ? "" : suggestion);
        if (input == null || input.toString().isBlank()) {
            return false;
        }
        String pattern = input.toString();
        try {
            LineFilter.of(List.of(pattern));
        } catch (InvalidRuleException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Invalid pattern", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        List<String> patterns = new ArrayList<>(options.ignorePatterns());
        if (!patterns.contains(pattern)) {
            patterns.add(pattern);
        }
        options = options.toBuilder().ignorePatterns(patterns).build();
        refresh();
        onChange.accept(options);
        return true;
    }

    private void removeIgnore() {
        List<String> selected = ignoreList.getSelectedValuesList();
        if (selected.isEmpty()) {
            return;
        }
        List<String> patterns = new ArrayList<>(options.ignorePatterns());
        patterns.removeAll(selected);
        options = options.toBuilder().ignorePatterns(patterns).build();
        refresh();
        onChange.accept(options);
    }

    private void restoreDefaults() {
        List<Rule> rules = new ArrayList<>();
        for (Rule r : options.rules()) {
            if (!r.builtin()) {
                rules.add(r);
            }
        }
        rules.addAll(RuleSet.defaults().rules());
        options = options.toBuilder().rules(RuleSet.of(rules)).build();
        refresh();
        onChange.accept(options);
    }

    private void refresh() {
        updating = true;
        try {
            ignoreCase.setSelected(options.ignoreCase());
            ignoreWhitespace.setSelected(options.ignoreWhitespace());
            detectMoves.setSelected(options.detectMoves());
            multiline.setSelected(options.multilineEntries());
            algorithm.setSelectedItem(options.algorithm());
            rulesModel.set(options.rules().rules());
            ignoreModel.clear();
            options.ignorePatterns().forEach(ignoreModel::addElement);
        } finally {
            updating = false;
        }
    }

    private void fire() {
        if (updating) {
            return;
        }
        options = options.toBuilder()
                .ignoreCase(ignoreCase.isSelected())
                .ignoreWhitespace(ignoreWhitespace.isSelected())
                .detectMoves(detectMoves.isSelected())
                .multilineEntries(multiline.isSelected())
                .algorithm((Algorithm) algorithm.getSelectedItem())
                .build();
        onChange.accept(options);
    }

    private final class RuleTableModel extends AbstractTableModel {
        private List<Rule> rules = List.of();

        void set(List<Rule> r) {
            rules = r;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rules.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int c) {
            return switch (c) {
                case 0 -> "";
                case 1 -> "Rule";
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
                case 1 -> rule.name();
                default -> rule.replacement();
            };
        }

        @Override
        public void setValueAt(Object value, int r, int c) {
            if (c != 0 || updating) {
                return;
            }
            Rule rule = rules.get(r);
            options = options.toBuilder().rules(options.rules().withEnabled(rule.id(), (Boolean) value)).build();
            rules = options.rules().rules();
            fireTableRowsUpdated(r, r);
            onChange.accept(options);
        }
    }
}
