// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.view;

import io.github.logdelta.gui.util.Icons;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

/// Find bar shown above the comparison (`Ctrl+F`). Searching starts while typing; Enter and
/// Shift+Enter step through matches, Escape closes the bar.
public final class SearchBar extends JPanel {

    /// Callbacks from the search bar.
    public interface Listener {
        /// The query or one of its options changed.
        void queryChanged(String query, boolean regex, boolean matchCase);

        /// Go to the next (`forward`) or previous match.
        void step(boolean forward);

        /// The bar was closed.
        void closed();
    }

    private final JTextField field = new JTextField(28);
    private final JToggleButton regex = new JToggleButton(".*");
    private final JToggleButton matchCase = new JToggleButton("Aa");
    private final JLabel status = new JLabel(" ");
    private final Timer typing;
    private Listener listener;

    /// Creates the (initially hidden) bar.
    public SearchBar() {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        setVisible(false);

        typing = new Timer(150, _ -> fire());
        typing.setRepeats(false);
        field.getDocument().addDocumentListener(new DocumentListener() {
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
        field.setMaximumSize(new Dimension(420, field.getPreferredSize().height + 4));
        field.putClientProperty("JTextField.placeholderText", "Find in both logs");
        field.setToolTipText("Find in both logs (Enter: next, Shift+Enter: previous, Esc: close)");
        bind(field, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), () -> step(true));
        bind(field, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK),
                () -> step(false));
        bind(this, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                this::close);

        regex.setToolTipText("Regular expression");
        matchCase.setToolTipText("Match case");
        regex.addActionListener(_ -> fire());
        matchCase.addActionListener(_ -> fire());

        JButton prev = iconButton(Icons.previous(), "Previous match (Shift+Enter)", () -> step(false));
        JButton next = iconButton(Icons.next(), "Next match (Enter)", () -> step(true));
        JButton close = new JButton("✕");
        close.setToolTipText("Close (Esc)");
        close.addActionListener(_ -> close());
        close.setFocusable(false);

        JLabel icon = new JLabel(Icons.search());
        add(icon);
        add(Box.createHorizontalStrut(6));
        add(field);
        add(Box.createHorizontalStrut(4));
        add(matchCase);
        add(regex);
        add(Box.createHorizontalStrut(8));
        add(prev);
        add(next);
        add(Box.createHorizontalStrut(10));
        add(status);
        add(Box.createHorizontalGlue());
        add(close);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /// Shows the bar and focuses the field, optionally pre-filling it.
    public void open(String initial) {
        setVisible(true);
        if (initial != null && !initial.isBlank()) {
            field.setText(initial);
        }
        field.requestFocusInWindow();
        field.selectAll();
        revalidate();
    }

    /// Hides the bar and clears highlights.
    public void close() {
        setVisible(false);
        revalidate();
        if (listener != null) {
            listener.closed();
        }
    }

    /// Updates the status text, e.g. `"3 of 17"`; `error` shows it in red.
    public void setStatus(String text, boolean error) {
        status.setText(text);
        Color c = error ? UIManager.getColor("nimbusRed") : UIManager.getColor("text");
        status.setForeground(c == null ? (error ? Color.RED : Color.DARK_GRAY) : c);
    }

    public String query() {
        return field.getText();
    }

    public boolean isRegex() {
        return regex.isSelected();
    }

    public boolean isMatchCase() {
        return matchCase.isSelected();
    }

    private void fire() {
        if (listener != null) {
            listener.queryChanged(field.getText(), regex.isSelected(), matchCase.isSelected());
        }
    }

    private void step(boolean forward) {
        if (typing.isRunning()) {
            typing.stop();
            fire();
        }
        if (listener != null) {
            listener.step(forward);
        }
    }

    private static JButton iconButton(javax.swing.Icon icon, String tip, Runnable action) {
        JButton b = new JButton(icon);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(_ -> action.run());
        return b;
    }

    private static void bind(JComponent c, int condition, KeyStroke key, Runnable action) {
        String name = "search-" + key;
        c.getInputMap(condition).put(key, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }
}
