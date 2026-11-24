// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.dialog;

import io.github.logdelta.core.Version;

import javax.swing.JOptionPane;
import java.awt.Component;

import static io.github.logdelta.gui.i18n.I18n.tr;

/// The About box. (The keyboard shortcut reference is
/// [io.github.logdelta.gui.command.ShortcutsDialog].)
public final class AboutDialog {

    private AboutDialog() {
    }

    /// Shows product and runtime information.
    public static void showAbout(Component parent) {
        String text = "<html><h2 style='margin:0'>" + Version.NAME + " " + Version.VERSION + "</h2>"
                + "<p>" + tr("Compare log files while ignoring what always changes.") + "</p><br>"
                + "<p>Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")<br>"
                + System.getProperty("os.name") + " " + System.getProperty("os.version") + "<br>"
                + tr("Maximum memory: {0} MB", Runtime.getRuntime().maxMemory() / (1024 * 1024)) + "</p><br>"
                + "<p>© 2026 Hana Eun-Seo · " + tr("GNU GPL v3 or later") + "<br>"
                + tr("This program comes with ABSOLUTELY NO WARRANTY.") + "<br>" + Version.HOMEPAGE + "</p></html>";
        JOptionPane.showMessageDialog(parent, text, tr("About {0}", Version.NAME), JOptionPane.PLAIN_MESSAGE,
                new javax.swing.ImageIcon(io.github.logdelta.gui.util.AppIcon.render(64)));
    }
}
