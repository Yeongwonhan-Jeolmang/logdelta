// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.sidebar;

import io.github.logdelta.core.analysis.FlakyLine;
import io.github.logdelta.core.diff.Algorithm;
import io.github.logdelta.core.engine.Detection;
import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.normalize.InvalidRuleException;
import io.github.logdelta.core.normalize.LineFilter;
import io.github.logdelta.core.normalize.Rule;
import io.github.logdelta.core.normalize.RuleSet;
import io.github.logdelta.core.preset.Preset;
import io.github.logdelta.core.preset.Presets;
import io.github.logdelta.core.structured.LogFormat;
import io.github.logdelta.core.suggest.RuleSuggestion;
import io.github.logdelta.core.timing.TimeWindow;
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
import javax.swing.DefaultListCellRenderer;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static io.github.logdelta.gui.i18n.I18n.tr;

/// Sidebar tab for the options that most often need adjusting while looking at a comparison:
/// the log format (structured fields, preset, time window), comparison flags, which rules are
/// active, and which lines to ignore. Every change is applied immediately (the controller
/// debounces re-comparison).
public final class RulesPanel extends JPanel {

    private final Consumer<DiffOptions> onChange;
    private final JCheckBox ignoreCase = new JCheckBox(tr("Ignore case"));
    private final JCheckBox ignoreWhitespace = new JCheckBox(tr("Ignore whitespace"));
    private final JCheckBox detectMoves = new JCheckBox(tr("Detect moved lines"));
    private final JCheckBox multiline = new JCheckBox(tr("Keep stack traces with their line"));
    private final JComboBox<LogFormat> format = new JComboBox<>(LogFormat.values());
    private final JComboBox<String> preset = new JComboBox<>(presetChoices());
    private final JTextField ignoredFields = new JTextField(14);
    private final JTextField since = new JTextField(7);
    private final JTextField until = new JTextField(7);
    private final JLabel detected = new JLabel(" ");
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
        flags.add(Section.caption(tr("Comparison")));
        for (JCheckBox b : List.of(ignoreCase, ignoreWhitespace, detectMoves, multiline)) {
            b.setOpaque(false);
            b.addActionListener(_ -> fire());
            flags.add(Section.compact(b));
        }
        ignoreCase.setToolTipText(tr("Treat upper- and lower-case letters as equal"));
        ignoreWhitespace.setToolTipText(tr("Treat runs of spaces and tabs as one space; ignore leading/trailing space"));
        detectMoves.setToolTipText(tr("Show identical lines that changed position as moved instead of removed+added"));
        multiline.setToolTipText("<html>" + tr("Compare a log line and its continuation lines (stack traces, wrapped "
                + "messages) as one entry, so a trace never gets matched to the wrong error") + "</html>");
        JPanel algo = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        algo.setOpaque(false);
        JLabel algoLabel = new JLabel(tr("Algorithm") + "  ");
        algoLabel.setLabelFor(algorithm);
        algo.add(algoLabel);
        algo.add(algorithm);
        algorithm.setToolTipText("<html>" + tr("Myers: shortest edit script (default)") + "<br>"
                + tr("Patience: anchors on unique lines; reads better when sections are reordered") + "</html>");
        algorithm.addActionListener(_ -> fire());
        flags.add(Section.compact(algo));

        flags.add(Section.caption(tr("Log format")));
        flags.add(Section.compact(formatGrid()));

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
        JButton edit = new JButton(tr("Edit rules…"));
        edit.setToolTipText(tr("Add, edit, reorder and test rules (Ctrl+R)"));
        edit.addActionListener(_ -> editRules.run());
        JButton all = new JButton(tr("All on"));
        all.addActionListener(_ -> {
            options = options.toBuilder().rules(options.rules().withAllEnabled(true)).build();
            refresh();
            onChange.accept(options);
        });
        JButton defaults = new JButton(tr("Defaults"));
        defaults.setToolTipText(tr("Restore the built-in rules and their default states (custom rules are kept)"));
        defaults.addActionListener(_ -> restoreDefaults());
        ruleButtons.add(edit);
        ruleButtons.add(all);
        ruleButtons.add(defaults);

        JPanel rules = new JPanel(new BorderLayout());
        JLabel rulesCaption = Section.caption(tr("Normalization rules"));
        table.getAccessibleContext().setAccessibleName(tr("Normalization rules"));
        rulesCaption.setBorder(BorderFactory.createEmptyBorder(14, 12, 6, 12));
        rules.add(rulesCaption, BorderLayout.NORTH);
        rules.add(tableScroll, BorderLayout.CENTER);
        rules.add(ruleButtons, BorderLayout.SOUTH);

        // Ignore patterns.
        ignoreList.setVisibleRowCount(4);
        ignoreList.setToolTipText(tr("Lines matching any of these regular expressions are left out of the comparison"));
        ignoreList.getAccessibleContext().setAccessibleName(tr("Ignored lines"));
        JPanel ignoreButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        JButton add = new JButton(tr("Add…"));
        add.addActionListener(_ -> addIgnore(null));
        JButton remove = new JButton(tr("Remove"));
        remove.addActionListener(_ -> removeIgnore());
        ignoreButtons.add(add);
        ignoreButtons.add(remove);
        JPanel ignore = new JPanel(new BorderLayout());
        JLabel ignoreCaption = Section.caption(tr("Ignored lines"));
        ignoreCaption.setBorder(BorderFactory.createEmptyBorder(8, 12, 6, 12));
        ignore.add(ignoreCaption, BorderLayout.NORTH);
        ignore.add(new JScrollPane(ignoreList), BorderLayout.CENTER);
        ignore.add(ignoreButtons, BorderLayout.SOUTH);

        // Suggestions (hidden unless the last comparison produced some).
        JLabel suggestionCaption = Section.caption(tr("Suggested rules"));
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

        // Options and suggestions scroll on their own, so a long list of suggestions never squeezes
        // the rule table out of view.
        JScrollPane topScroll = new JScrollPane(top);
        topScroll.setBorder(BorderFactory.createEmptyBorder());
        topScroll.getVerticalScrollBar().setUnitIncrement(16);
        topScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(rules, BorderLayout.CENTER);
        bottom.add(ignore, BorderLayout.SOUTH);
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, topScroll,
                bottom);
        split.setResizeWeight(0.5);
        split.setBorder(null);
        split.setContinuousLayout(true);
        add(split, BorderLayout.CENTER);
    }

    /// Shows rules that would hide run-specific differences of the last comparison, each with an
    /// Apply button. An empty list hides the section.
    public void setSuggestions(List<RuleSuggestion> list) {
        setSuggestions(list, List.of());
    }

    /// Shows suggested rules and fixes for flaky lines (lines that vary between good runs).
    public void setSuggestions(List<RuleSuggestion> list, List<FlakyLine> flaky) {
        suggestionList.removeAll();
        for (RuleSuggestion sug : list) {
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);
            row.setAlignmentX(LEFT_ALIGNMENT);
            JLabel text = new JLabel("<html><div style='width:180px'>" + tr("<b>{0}</b> lines {1}", sug.resolves(),
                    Text.escapeHtml(sug.summary())) + "</div></html>");
            text.setToolTipText("<html>" + tr("Masks {0} as {1}", "<code>" + Text.escapeHtml(sug.rule().regex())
                    + "</code>", "<code>" + Text.escapeHtml(sug.rule().replacement()) + "</code>") + "<br>"
                    + tr("Example:") + " <code>" + Text.escapeHtml(Text.truncate(sug.exampleRight(), 100))
                    + "</code></html>");
            JButton apply = new JButton(tr("Apply"));
            apply.setToolTipText(tr("Add this rule; the comparison updates immediately"));
            apply.getAccessibleContext().setAccessibleDescription(sug.summary());
            apply.addActionListener(_ -> apply(sug));
            row.add(text, BorderLayout.CENTER);
            row.add(apply, BorderLayout.EAST);
            row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 4));
            suggestionList.add(row);
        }
        for (FlakyLine f : flaky) {
            if (f.rules().isEmpty() && f.ignorePattern() == null) {
                continue;
            }
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);
            row.setAlignmentX(LEFT_ALIGNMENT);
            String what = f.kind() == FlakyLine.Kind.VALUES ? tr("changes between good runs")
                    : tr("appears in some good runs only");
            String similar = f.similarLines() > 0 ? " " + tr("(+{0} similar)", f.similarLines()) : "";
            JLabel text = new JLabel("<html><div style='width:180px'>" + tr("Line {0} {1}", f.lineNumber(), what)
                    + similar + ": <code>" + Text.escapeHtml(Text.truncate(
                    io.github.logdelta.core.util.LogText.message(f.text()), 50)) + "</code></div></html>");
            text.setToolTipText("<html><code>" + Text.escapeHtml(Text.truncate(f.text(), 140)) + "</code>"
                    + (f.example() == null ? "" : "<br>" + tr("Another run:") + " <code>"
                    + Text.escapeHtml(Text.truncate(f.example(), 140)) + "</code>") + "</html>");
            JButton fix = new JButton(f.rule() != null ? tr("Mask") : tr("Ignore"));
            fix.setToolTipText(f.rule() != null
                    ? tr("Add a rule that masks the part that varies: {0}", String.join("  ",
                    f.rules().stream().map(Rule::regex).toList()))
                    : tr("Leave out lines matching {0}", f.ignorePattern()));
            fix.addActionListener(_ -> {
                if (f.rule() != null) {
                    applyRules(f.rules());
                } else {
                    List<String> patterns = new ArrayList<>(options.ignorePatterns());
                    if (!patterns.contains(f.ignorePattern())) {
                        patterns.add(f.ignorePattern());
                    }
                    options = options.toBuilder().ignorePatterns(patterns).build();
                    refresh();
                    onChange.accept(options);
                }
            });
            row.add(text, BorderLayout.CENTER);
            row.add(fix, BorderLayout.EAST);
            row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 4));
            suggestionList.add(row);
        }
        suggestions.setVisible(suggestionList.getComponentCount() > 0);
        revalidate();
        repaint();
    }

    /// Shows what was detected in the last comparison (log format and preset).
    public void setDetection(Detection d) {
        String text = d == null || describe(d).isEmpty() ? tr("Detected: plain text")
                : tr("Detected: {0}", describe(d));
        detected.setText(text);
        if (d != null && d.preset() != null) {
            StringBuilder tip = new StringBuilder("<html><b>").append(Text.escapeHtml(d.preset().name()))
                    .append("</b><br>").append(Text.escapeHtml(d.preset().description())).append("<br>")
                    .append(tr("Extra rules:"));
            for (Rule r : d.preset().rules()) {
                tip.append("<br>\u2022 ").append(Text.escapeHtml(r.name()));
            }
            detected.setToolTipText(tip.append("</html>").toString());
        } else {
            detected.setToolTipText(null);
        }
    }

    /// The detected format and preset in the interface language, e.g. "JSON lines (detected)".
    public static String describe(Detection d) {
        StringBuilder sb = new StringBuilder();
        if (d.format().isStructured()) {
            sb.append(tr(d.format().displayName())).append(d.formatDetected() ? " " + tr("(detected)") : "");
        }
        if (d.preset() != null) {
            sb.append(sb.isEmpty() ? "" : ", ").append(d.preset().name())
                    .append(d.presetDetected() ? " " + tr("(detected)") : "");
        }
        return sb.toString();
    }

    private JPanel formatGrid() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        int y = 0;
        addRow(p, y++, tr("Lines"), format);
        format.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel,
                                                          boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof LogFormat f) {
                    setText(tr(f.displayName()));
                    setToolTipText(tr(f.description()));
                }
                return this;
            }
        });
        format.setToolTipText(tr("How lines are read: JSON and logfmt lines are compared field by field"));
        format.addActionListener(_ -> fire());
        addRow(p, y++, tr("Preset"), preset);
        preset.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel,
                                                          boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                setText(presetLabel((String) value));
                return this;
            }
        });
        preset.setToolTipText(tr("Built-in profile for a known log format; Automatic picks one when it fits"));
        preset.addActionListener(_ -> fire());
        addRow(p, y++, tr("Ignored fields"), ignoredFields);
        ignoredFields.setToolTipText("<html>" + tr("Fields of JSON/logfmt lines left out of the comparison, "
                + "separated by commas. * matches anything; a name without a dot also matches nested fields.")
                + "</html>");
        JPanel window = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        window.setOpaque(false);
        window.add(since);
        window.add(new JLabel("\u2192"));
        window.add(until);
        addRow(p, y++, tr("Time window"), window);
        since.getAccessibleContext().setAccessibleName(tr("Compare from"));
        until.getAccessibleContext().setAccessibleName(tr("Compare until"));
        String windowTip = "<html>" + tr("Only compare part of each run. Examples:") + "<br><code>+2m</code> "
                + tr("two minutes into the run") + "<br><code>08:49:03</code> " + tr("a time of day")
                + "<br><code>2026-11-12T08:49</code> " + tr("a date and time") + "<br>"
                + tr("Leave empty for the whole log.") + "</html>";
        since.setToolTipText(windowTip);
        until.setToolTipText(windowTip);
        for (JTextField f : List.of(ignoredFields, since, until)) {
            f.addActionListener(_ -> fire());
            f.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    fire();
                }
            });
        }
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = y;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 0, 0, 0);
        detected.setForeground(Section.muted());
        p.add(detected, c);
        return p;
    }

    private static void addRow(JPanel p, int y, String label, java.awt.Component field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, 8);
        JLabel l = new JLabel(label);
        l.setLabelFor(field);
        p.add(l, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 0, 2, 0);
        p.add(field, c);
    }

    private static String[] presetChoices() {
        List<String> ids = new ArrayList<>(List.of(Presets.AUTO, Presets.NONE));
        for (Preset p : Presets.all()) {
            ids.add(p.id());
        }
        return ids.toArray(String[]::new);
    }

    private static String presetLabel(String id) {
        if (id == null) {
            return "";
        }
        return switch (id) {
            case Presets.AUTO -> tr("Automatic");
            case Presets.NONE -> tr("None");
            default -> {
                Preset p = Presets.find(id);
                yield p == null ? id : p.name();
            }
        };
    }

    private void apply(RuleSuggestion sug) {
        applyRule(sug.rule());
    }

    private void applyRules(List<Rule> list) {
        for (int i = list.size() - 1; i >= 0; i--) {
            addRule(list.get(i));
        }
        refresh();
        onChange.accept(options);
    }

    private void applyRule(Rule rule) {
        addRule(rule);
        refresh();
        onChange.accept(options);
    }

    private void addRule(Rule rule) {
        String id = rule.id();
        for (int n = 2; options.rules().find(id) != null; n++) {
            id = rule.id() + "-" + n;
        }
        Rule unique = id.equals(rule.id()) ? rule : rule.toBuilder().id(id).build();
        options = options.toBuilder().rules(options.rules().withAt(0, unique)).build();
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
                tr("Ignore lines matching this regular expression:"), tr("Ignore lines"),
                JOptionPane.PLAIN_MESSAGE, null, null, suggestion == null ? "" : suggestion);
        if (input == null || input.toString().isBlank()) {
            return false;
        }
        String pattern = input.toString();
        try {
            LineFilter.of(List.of(pattern));
        } catch (InvalidRuleException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), tr("Invalid pattern"), JOptionPane.ERROR_MESSAGE);
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
            format.setSelectedItem(options.format());
            preset.setSelectedItem(options.preset());
            ignoredFields.setText(String.join(", ", options.ignoreFields()));
            TimeWindow w = options.timeWindow();
            since.setText(w == null || w.since() == null ? "" : w.since().text());
            until.setText(w == null || w.until() == null ? "" : w.until().text());
            markValid(since, true);
            markValid(until, true);
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
        TimeWindow window;
        boolean sinceOk = true;
        boolean untilOk = true;
        try {
            TimeWindow.parse(since.getText(), null);
        } catch (IllegalArgumentException e) {
            sinceOk = false;
        }
        try {
            TimeWindow.parse(null, until.getText());
        } catch (IllegalArgumentException e) {
            untilOk = false;
        }
        window = options.timeWindow();
        if (sinceOk && untilOk) {
            try {
                window = TimeWindow.parse(since.getText(), until.getText());
            } catch (IllegalArgumentException e) {
                untilOk = false; // ends before it starts
            }
        }
        markValid(since, sinceOk);
        markValid(until, untilOk);
        List<String> fields = new ArrayList<>();
        for (String f : ignoredFields.getText().split(",")) {
            if (!f.isBlank()) {
                fields.add(f.strip());
            }
        }
        DiffOptions next = options.toBuilder()
                .ignoreCase(ignoreCase.isSelected())
                .ignoreWhitespace(ignoreWhitespace.isSelected())
                .detectMoves(detectMoves.isSelected())
                .multilineEntries(multiline.isSelected())
                .algorithm((Algorithm) algorithm.getSelectedItem())
                .format((LogFormat) format.getSelectedItem())
                .preset((String) preset.getSelectedItem())
                .ignoreFields(fields)
                .timeWindow(window)
                .build();
        if (next.equals(options)) {
            return;
        }
        options = next;
        onChange.accept(options);
    }

    private static void markValid(JTextField f, boolean valid) {
        Color normal = UIManager.getColor("TextField.foreground");
        f.setForeground(valid ? normal : new Color(0xCF222E));
        f.getAccessibleContext().setAccessibleDescription(valid ? null : tr("Not a valid time"));
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
                case 1 -> tr("Rule");
                default -> tr("Replaced by");
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
