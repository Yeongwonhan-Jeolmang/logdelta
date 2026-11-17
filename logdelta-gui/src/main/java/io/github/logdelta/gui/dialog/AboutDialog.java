// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.dialog;

import io.github.logdelta.core.Version;

import javax.swing.JOptionPane;
import java.awt.Component;

/// The About box and the keyboard shortcut reference.
public final class AboutDialog {

    private AboutDialog() {
    }

    /// Shows product and runtime information.
    public static void showAbout(Component parent) {
        String text = "<html><h2 style='margin:0'>" + Version.NAME + " " + Version.VERSION + "</h2>"
                + "<p>Compare log files while ignoring what always changes.</p><br>"
                + "<p>Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")<br>"
                + System.getProperty("os.name") + " " + System.getProperty("os.version") + "</p><br>"
                + "<p>\u00a9 2026 Hana Eun-Seo \u00b7 GNU GPL v3 or later<br>"
                + "This program comes with ABSOLUTELY NO WARRANTY.<br>" + Version.HOMEPAGE + "</p></html>";
        JOptionPane.showMessageDialog(parent, text, "About " + Version.NAME, JOptionPane.PLAIN_MESSAGE,
                new javax.swing.ImageIcon(io.github.logdelta.gui.util.AppIcon.render(64)));
    }

    /// Shows the keyboard shortcuts.
    public static void showShortcuts(Component parent) {
        String mod = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac") ? "⌘" : "Ctrl+";
        String[][] rows = {
            {mod + "O", "Open baseline and candidate"},
            {mod + "Shift+O", "Open URLs"},
            {mod + "Shift+V", "Paste the clipboard as the candidate"},
            {mod + "Shift+D", "Compare two folders"},
            {mod + "T / " + mod + "W", "New tab / close tab"},
            {mod + "S", "Save the session (logs, rules, bookmarks, notes)"},
            {mod + "Shift+S", "Swap baseline and candidate"},
            {"F5", "Compare again"},
            {mod + "Shift+L", "Live tail: follow logs that are still being written"},
            {"F7 / Shift+F7", "Next / previous change"},
            {"F8 / Shift+F8", "Next / previous new problem"},
            {mod + "B / " + mod + "Shift+B", "Toggle bookmark / edit its note"},
            {"F2 / Shift+F2", "Next / previous bookmark"},
            {mod + "F", "Find in both logs"},
            {mod + "L", "Filter lines by text"},
            {"Alt+1 \u2026 Alt+4", "Show all lines / changes / new problems / errors"},
            {mod + "U", "Collapse unchanged lines"},
            {mod + "E", "Expand all collapsed lines"},
            {mod + "M", "Show normalized text"},
            {"Alt+Z / Alt+T", "Wrap long lines / show elapsed time"},
            {mod + "R", "Edit rules"},
            {mod + "I", "Ignore lines like the selected one"},
            {mod + "C", "Copy the selected candidate line"},
            {mod + "= / " + mod + "- / " + mod + "0", "Zoom in / out / reset"},
            {"\u2191 \u2193 PgUp PgDn", "Move the selection"},
            {"\u2190 \u2192, Shift+wheel", "Scroll horizontally"},
            {"Drag the middle divider", "Resize the panes (double-click: equal widths)"},
            {"Alt+Shift+\u2192 / \u2190", "Widen the baseline / candidate pane"},
        };
        StringBuilder sb = new StringBuilder("<html><table cellpadding='3'>");
        for (String[] r : rows) {
            sb.append("<tr><td><b>").append(r[0]).append("</b></td><td>").append(r[1]).append("</td></tr>");
        }
        sb.append("</table></html>");
        JOptionPane.showMessageDialog(parent, sb.toString(), "Keyboard shortcuts", JOptionPane.PLAIN_MESSAGE);
    }
}
