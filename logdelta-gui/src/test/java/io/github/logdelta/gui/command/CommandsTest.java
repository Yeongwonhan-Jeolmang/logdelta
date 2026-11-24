// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Command palette, shortcut reference and menu walking. Swing menus work headless.
class CommandsTest {

    private static final KeyStroke PALETTE =
            KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);

    private static JMenuBar menus() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem open = new JMenuItem("Open Files…");
        open.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        file.add(open);
        file.addSeparator();
        JMenuItem export = new JMenuItem("Export Report…");
        export.setEnabled(false);
        file.add(export);
        JMenu view = new JMenu("View");
        JMenu theme = new JMenu("Theme");
        JCheckBoxMenuItem dark = new JCheckBoxMenuItem("Dark", true);
        theme.add(dark);
        view.add(theme);
        JMenuItem palette = new JMenuItem("Command Palette…");
        palette.setAccelerator(PALETTE);
        view.add(palette);
        bar.add(file);
        bar.add(view);
        return bar;
    }

    @Test
    @DisplayName("collect walks submenus and skips separators")
    void collect() {
        List<MenuCommands.Entry> entries = MenuCommands.collect(menus());
        assertEquals(List.of("File › Open Files…", "File › Export Report…", "View › Theme › Dark",
                "View › Command Palette…"), entries.stream().map(MenuCommands.Entry::fullName).toList());
        assertTrue(entries.get(2).checked());
        assertFalse(entries.get(1).enabled());
    }

    @Test
    @DisplayName("fuzzy scores prefer whole words and consecutive letters")
    void score() {
        assertEquals(-1, MenuCommands.score("xyz", "Open Files"));
        assertEquals(0, MenuCommands.score("  ", "anything"));
        assertTrue(MenuCommands.score("open", "Open Files") > MenuCommands.score("open", "Reopen Tabs"));
        assertTrue(MenuCommands.score("of", "Open Files") > 0);
        assertTrue(MenuCommands.score("files", "Open Files") > MenuCommands.score("ofs", "Open Files"));
    }

    @Test
    @DisplayName("the palette lists enabled matches first and searches menu paths")
    void filter() {
        List<MenuCommands.Entry> entries = MenuCommands.collect(menus());
        List<MenuCommands.Entry> found = CommandPalette.filter(entries, "theme");
        assertEquals("Dark", found.get(0).name());
        List<MenuCommands.Entry> e = CommandPalette.filter(entries, "e");
        assertEquals("Export Report…", e.get(e.size() - 1).name(), "disabled commands sort last");
        assertTrue(CommandPalette.filter(entries, "zzz").isEmpty());
        assertEquals(entries.size(), CommandPalette.filter(entries, "").size());
    }

    @Test
    @DisplayName("shortcut names and the reference table")
    void shortcuts() {
        String palette = MenuCommands.describe(PALETTE);
        assertTrue(palette.endsWith("P"), palette);
        List<String[]> rows = ShortcutsDialog.rows(menus(), PALETTE);
        assertEquals(palette, rows.get(0)[0]);
        assertEquals(1, rows.stream().filter(r -> r[0].equals(palette)).count(), "the palette is listed once");
        assertTrue(rows.stream().anyMatch(r -> r[1].equals("Open Files…") && r[2].equals("File")));
        String text = ShortcutsDialog.asText(rows);
        assertTrue(text.contains("Open Files…  (File)"), text);
    }
}
