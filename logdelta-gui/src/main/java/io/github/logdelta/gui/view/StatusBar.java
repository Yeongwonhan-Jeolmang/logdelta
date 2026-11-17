// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.view;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;

/// Bottom bar: a message on the left, progress while comparing, details on the right.
public final class StatusBar extends JPanel {

    private final JLabel message = new JLabel("Ready");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JButton cancel = new JButton("Cancel");
    private final JLabel details = new JLabel();

    /// @param onCancel invoked when the user cancels a running comparison
    public StatusBar(Runnable onCancel) {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        Color line = UIManager.getColor("nimbusBorder");
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, line == null ? Color.LIGHT_GRAY : line),
                BorderFactory.createEmptyBorder(3, 10, 3, 10)));
        Font small = message.getFont().deriveFont(message.getFont().getSize2D() - 1f);
        message.setFont(small);
        details.setFont(small);
        progress.setPreferredSize(new Dimension(160, 14));
        progress.setMaximumSize(new Dimension(160, 14));
        progress.setVisible(false);
        cancel.setVisible(false);
        cancel.setFocusable(false);
        cancel.putClientProperty("JComponent.sizeVariant", "mini");
        cancel.addActionListener(_ -> onCancel.run());

        add(message);
        add(Box.createHorizontalStrut(12));
        add(progress);
        add(Box.createHorizontalStrut(6));
        add(cancel);
        add(Box.createHorizontalGlue());
        add(details);
    }

    /// Shows a message on the left.
    public void setMessage(String text) {
        message.setText(text);
    }

    /// Shows details on the right (algorithm, timing, encodings).
    public void setDetails(String text) {
        details.setText(text);
    }

    /// Shows or hides the progress indicator; `fraction < 0` means indeterminate.
    public void setBusy(boolean busy, String stage, double fraction) {
        progress.setVisible(busy);
        cancel.setVisible(busy);
        if (busy) {
            progress.setIndeterminate(fraction < 0);
            if (fraction >= 0) {
                progress.setValue((int) (fraction * 1000));
            }
            if (stage != null) {
                message.setText(stage + "…");
            }
        }
    }
}
