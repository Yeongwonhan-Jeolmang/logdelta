// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.view;

import io.github.logdelta.gui.model.ViewFilter;
import io.github.logdelta.gui.util.Icons;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/// A strip above the comparison that narrows what is shown: all lines, only changes, only new
/// problems or only errors, optionally combined with a text filter.
///
/// Unlike search, which jumps between matches, the filter hides everything else; hidden runs
/// are shown as a single "hidden by the filter" row.
public final class FilterBar extends JPanel {

    private final Map<ViewFilter.Mode, JToggleButton> modes = new EnumMap<>(ViewFilter.Mode.class);
    private final JTextField text = new JTextField(18);
    private final JLabel count = new JLabel(" ");
    private final Timer typing;
    private final Consumer<ViewFilter> onChange;
    private boolean updating;

    /// @param onChange receives the new filter whenever the user changes it
    public FilterBar(Consumer<ViewFilter> onChange) {
        this.onChange = onChange;
        setName("tour.filter");
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));

        JLabel show = new JLabel("Show");
        add(show);
        add(Box.createHorizontalStrut(6));
        ButtonGroup group = new ButtonGroup();
        for (ViewFilter.Mode m : ViewFilter.Mode.values()) {
            JToggleButton b = new JToggleButton(m.label());
            b.setFocusable(false);
            b.putClientProperty("JComponent.sizeVariant", "small");
            b.setToolTipText(switch (m) {
                case ALL -> "Every line; unchanged regions may be collapsed";
                case CHANGES -> "Only lines that differ";
                case PROBLEMS -> "Only new warnings and errors, with their stack traces";
                case ERRORS -> "Only ERROR and FATAL lines, on either side";
            });
            b.addActionListener(_ -> fire());
            group.add(b);
            modes.put(m, b);
            add(b);
        }
        modes.get(ViewFilter.Mode.ALL).setSelected(true);

        add(Box.createHorizontalStrut(10));
        typing = new Timer(200, _ -> fire());
        typing.setRepeats(false);
        text.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                typing.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                typing.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                typing.restart();
            }
        });
        text.putClientProperty("JTextField.placeholderText", "Only lines containing…");
        text.setToolTipText("Show only lines containing this text (either side, ignoring case). Esc clears it.");
        text.setMaximumSize(new Dimension(320, text.getPreferredSize().height + 4));
        text.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear");
        text.getActionMap().put("clear", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                text.setText("");
            }
        });
        add(new JLabel(Icons.search()));
        add(Box.createHorizontalStrut(4));
        add(text);
        JButton clear = new JButton("✕");
        clear.setToolTipText("Clear the filter");
        clear.setFocusable(false);
        clear.addActionListener(_ -> setFilter(ViewFilter.NONE, true));
        add(Box.createHorizontalStrut(4));
        add(clear);
        add(Box.createHorizontalStrut(12));
        add(count);
        add(Box.createHorizontalGlue());
    }

    /// Shows `f` in the controls; with `notify`, also reports it as a user change.
    public void setFilter(ViewFilter f, boolean notify) {
        updating = true;
        try {
            modes.get(f.mode()).setSelected(true);
            if (!text.getText().equals(f.text())) {
                text.setText(f.text());
            }
            typing.stop();
        } finally {
            updating = false;
        }
        if (notify) {
            onChange.accept(f);
        }
    }

    /// The filter currently shown.
    public ViewFilter filter() {
        ViewFilter.Mode mode = ViewFilter.Mode.ALL;
        for (Map.Entry<ViewFilter.Mode, JToggleButton> e : modes.entrySet()) {
            if (e.getValue().isSelected()) {
                mode = e.getKey();
            }
        }
        return new ViewFilter(mode, text.getText());
    }

    /// Updates the "showing N of M" label.
    public void setCounts(int shown, int total, boolean active) {
        count.setText(active ? String.format(Locale.ROOT, "%,d of %,d lines", shown, total) : " ");
    }

    /// Moves the keyboard focus to the text filter.
    public void focusText() {
        text.requestFocusInWindow();
        text.selectAll();
    }

    private void fire() {
        if (!updating) {
            onChange.accept(filter());
        }
    }
}
