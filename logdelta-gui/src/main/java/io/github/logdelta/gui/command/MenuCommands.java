// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.command;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// Collects the commands of a menu bar, so that the command palette and the shortcut reference
/// are always complete and never out of date.
public final class MenuCommands {

    private MenuCommands() {
    }

    /// A command reachable from the menu bar.
    ///
    /// @param path        menu names leading to the item, e.g. `["View", "Theme"]`
    /// @param name        the item's text
    /// @param accelerator its keyboard shortcut, or `null`
    /// @param item        the menu item (used to run the command and to read its state)
    public record Entry(List<String> path, String name, KeyStroke accelerator, JMenuItem item) {

        /// `View › Theme › Dark`.
        public String fullName() {
            return path.isEmpty() ? name : String.join(" › ", path) + " › " + name;
        }

        /// Whether the item is a check box or radio button that is currently selected.
        public boolean checked() {
            return (item instanceof JCheckBoxMenuItem || item instanceof JRadioButtonMenuItem) && item.isSelected();
        }

        /// Whether the command can run now.
        public boolean enabled() {
            return item.isEnabled();
        }

        /// Runs the command as if its menu item had been clicked.
        public void run() {
            item.doClick(0);
        }
    }

    /// Every item of `bar`, depth first, in menu order.
    public static List<Entry> collect(JMenuBar bar) {
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenu m = bar.getMenu(i);
            if (m != null) {
                walk(m, List.of(m.getText()), out);
            }
        }
        return out;
    }

    private static void walk(JMenu menu, List<String> path, List<Entry> out) {
        for (Component c : menu.getMenuComponents()) {
            if (c instanceof JMenu sub) {
                List<String> p = new ArrayList<>(path);
                p.add(sub.getText());
                walk(sub, List.copyOf(p), out);
            } else if (c instanceof JMenuItem item && item.getText() != null && !item.getText().isBlank()) {
                out.add(new Entry(path, item.getText(), item.getAccelerator(), item));
            }
        }
    }

    /// Human-readable shortcut: `Ctrl+Shift+P`, or `⌘⇧P` on macOS.
    public static String describe(KeyStroke k) {
        if (k == null) {
            return "";
        }
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        int m = k.getModifiers();
        StringBuilder sb = new StringBuilder();
        if (mac) {
            if ((m & InputEvent.CTRL_DOWN_MASK) != 0) {
                sb.append('⌃');
            }
            if ((m & InputEvent.ALT_DOWN_MASK) != 0) {
                sb.append('⌥');
            }
            if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) {
                sb.append('⇧');
            }
            if ((m & InputEvent.META_DOWN_MASK) != 0) {
                sb.append('⌘');
            }
        } else {
            if ((m & InputEvent.CTRL_DOWN_MASK) != 0) {
                sb.append("Ctrl+");
            }
            if ((m & InputEvent.ALT_DOWN_MASK) != 0) {
                sb.append("Alt+");
            }
            if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) {
                sb.append("Shift+");
            }
            if ((m & InputEvent.META_DOWN_MASK) != 0) {
                sb.append("Meta+");
            }
        }
        sb.append(keyName(k.getKeyCode()));
        return sb.toString();
    }

    private static String keyName(int code) {
        return switch (code) {
            case KeyEvent.VK_EQUALS -> "=";
            case KeyEvent.VK_MINUS -> "-";
            case KeyEvent.VK_SLASH -> "/";
            case KeyEvent.VK_PAGE_UP -> "PgUp";
            case KeyEvent.VK_PAGE_DOWN -> "PgDn";
            case KeyEvent.VK_LEFT -> "←";
            case KeyEvent.VK_RIGHT -> "→";
            case KeyEvent.VK_UP -> "↑";
            case KeyEvent.VK_DOWN -> "↓";
            default -> KeyEvent.getKeyText(code);
        };
    }

    /// Scores how well `query` matches `text`: `-1` for no match, higher is better. Every
    /// character of the query must appear in order; consecutive characters, word starts and an
    /// early first match score higher. Case is ignored.
    public static int score(String query, String text) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return 0;
        }
        String t = text.toLowerCase(Locale.ROOT);
        int contains = t.indexOf(q);
        if (contains >= 0) {
            boolean wordStart = contains == 0 || !Character.isLetterOrDigit(t.charAt(contains - 1));
            return 1000 - contains + (wordStart ? 200 : 0) + q.length() * 10;
        }
        int score = 0;
        int ti = 0;
        int previous = -2;
        for (int qi = 0; qi < q.length(); qi++) {
            char c = q.charAt(qi);
            if (c == ' ') {
                continue;
            }
            int found = t.indexOf(c, ti);
            if (found < 0) {
                return -1;
            }
            score += found == previous + 1 ? 15 : 1;
            if (found == 0 || !Character.isLetterOrDigit(t.charAt(found - 1))) {
                score += 10;
            }
            previous = found;
            ti = found + 1;
        }
        return score;
    }
}
