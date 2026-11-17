// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.dialog;

import io.github.logdelta.core.io.LogSources;
import io.github.logdelta.gui.model.Source;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;

/// Asks for a baseline and a candidate given as URLs (or paths), with an optional request
/// header for servers that need authentication, such as a CI system's artifact store.
public final class OpenUrlDialog {

    /// What the user entered. Either source may be `null` if its field was left empty.
    ///
    /// @param left          baseline
    /// @param right         candidate
    /// @param remoteOptions download settings including the optional header
    public record Result(Source left, Source right, LogSources.RemoteOptions remoteOptions) {
    }

    private OpenUrlDialog() {
    }

    /// Shows the dialog.
    ///
    /// @return the entries, or `null` if the dialog was cancelled or left empty
    public static Result show(Component parent) {
        JTextField left = new JTextField(46);
        JTextField right = new JTextField(46);
        JTextField headerName = new JTextField("Authorization", 14);
        JPasswordField headerValue = new JPasswordField(30);
        left.putClientProperty("JTextField.placeholderText", "https://ci.example.com/job/41/artifact/app.log");
        right.putClientProperty("JTextField.placeholderText", "https://ci.example.com/job/42/artifact/app.log");
        headerValue.setToolTipText("Optional, e.g. 'Bearer <token>'. Kept in memory only; never saved.");
        String clip = clipboardUrl();
        if (clip != null) {
            right.setText(clip);
        }

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        row(p, c, 0, "Baseline", left);
        row(p, c, 1, "Candidate", right);
        c.gridy = 2;
        c.gridx = 0;
        c.gridwidth = 2;
        JLabel hint = new JLabel("<html><small>Paths work here too. Gzip-compressed logs are detected "
                + "automatically.</small></html>");
        p.add(hint, c);
        c.gridwidth = 1;
        JPanel header = new JPanel(new GridBagLayout());
        GridBagConstraints h = new GridBagConstraints();
        h.insets = new Insets(0, 0, 0, 6);
        h.fill = GridBagConstraints.HORIZONTAL;
        header.add(headerName, h);
        h.weightx = 1;
        header.add(headerValue, h);
        row(p, c, 3, "Header", header);

        int answer = JOptionPane.showConfirmDialog(parent, p, "Open URLs", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return null;
        }
        try {
            Source l = left.getText().isBlank() ? null : Source.parse(left.getText());
            Source r = right.getText().isBlank() ? null : Source.parse(right.getText());
            if (l == null && r == null) {
                return null;
            }
            LogSources.RemoteOptions options = LogSources.RemoteOptions.DEFAULT;
            String value = new String(headerValue.getPassword()).strip();
            if (!value.isEmpty() && !headerName.getText().isBlank()) {
                options = options.withHeader(headerName.getText().strip(), value);
            }
            return new Result(l, r, options);
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(parent, e.getMessage(), "Open URLs", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private static void row(JPanel p, GridBagConstraints c, int y, String label, Component field) {
        c.gridy = y;
        c.gridx = 0;
        c.weightx = 0;
        p.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        p.add(field, c);
    }

    /// A URL on the clipboard, to pre-fill the candidate field.
    private static String clipboardUrl() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            String s = data == null ? "" : data.toString().strip();
            return LogSources.isUrl(s) && !s.contains("\n") ? s : null;
        } catch (Exception _) {
            return null;
        }
    }
}
