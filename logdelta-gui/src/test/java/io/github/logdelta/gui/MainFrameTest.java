// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui;

import io.github.logdelta.gui.command.MenuCommands;
import io.github.logdelta.gui.i18n.I18n;
import io.github.logdelta.gui.settings.AppSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Builds the real main window. Needs a display, so it runs in CI under `xvfb-run` with
/// `-Dlogdelta.headless=false` and is skipped elsewhere.
class MainFrameTest {

    @Test
    @DisplayName("menus have unique shortcuts and every icon-only button has a name")
    void menusAndAccessibility() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        Preferences node = Preferences.userRoot().node("io/github/logdelta/test-frame-" + System.nanoTime());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (String language : new String[] {"en", "ko"}) {
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = null;
                try {
                    AppSettings settings = new AppSettings(node, Files.createTempDirectory("logdelta-frame"));
                    settings.setTourShown(true);
                    I18n.setLanguage(language);
                    frame = new MainFrame(settings);
                    List<MenuCommands.Entry> commands = MenuCommands.collect(frame.getJMenuBar());
                    assertTrue(commands.size() > 40, language + ": " + commands.size() + " commands");

                    Map<KeyStroke, String> seen = new HashMap<>();
                    for (MenuCommands.Entry e : commands) {
                        if (e.accelerator() != null) {
                            String other = seen.put(e.accelerator(), e.fullName());
                            assertTrue(other == null, "shortcut used twice: " + other + " and " + e.fullName());
                        }
                    }
                    assertTrue(seen.containsKey(MainFrame.PALETTE_KEY), "the palette has its shortcut");

                    List<String> unnamed = new ArrayList<>();
                    unnamedButtons(frame.getContentPane(), unnamed);
                    assertTrue(unnamed.isEmpty(), "buttons without a readable name: " + unnamed);
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    if (frame != null) {
                        frame.dispose();
                    }
                    I18n.setLanguage("en");
                }
            });
            if (failure.get() != null) {
                break;
            }
        }
        node.removeNode();
        if (failure.get() instanceof AssertionError a) {
            throw a;
        } else if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    @DisplayName("pasted text survives being reopened twice")
    void reopenTabsTwice() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        Preferences node = Preferences.userRoot().node("io/github/logdelta/test-reopen-" + System.nanoTime());
        java.nio.file.Path dir = Files.createTempDirectory("logdelta-reopen");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                AppSettings settings = new AppSettings(node, dir);
                settings.setTourShown(true);
                settings.setRestoreTabs(true);
                java.lang.reflect.Method save = MainFrame.class.getDeclaredMethod("saveOpenTabs", boolean.class);
                save.setAccessible(true);

                MainFrame first = new MainFrame(settings);
                first.selectedComparison().open(
                        new io.github.logdelta.gui.model.Source.Text(
                                io.github.logdelta.core.io.LogFile.ofText("pasted-a", "one\ntwo\n")),
                        new io.github.logdelta.gui.model.Source.Text(
                                io.github.logdelta.core.io.LogFile.ofText("pasted-b", "one\nthree\n")));
                save.invoke(first, true);
                first.dispose();

                for (int round = 0; round < 2; round++) {
                    MainFrame again = new MainFrame(settings);
                    assertTrue(again.restoreOpenTabs(), "round " + round);
                    save.invoke(again, true);
                    again.dispose();
                }
                List<String> entries = settings.openTabs();
                assertTrue(entries.size() == 1, entries.toString());
                var session = io.github.logdelta.core.session.SessionIO.read(
                        java.nio.file.Path.of(entries.getFirst().split("\t")[1]));
                assertTrue(Files.readString(java.nio.file.Path.of(session.right())).contains("three"),
                        "the pasted text is still there");
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        node.removeNode();
        if (failure.get() instanceof AssertionError a) {
            throw a;
        } else if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    /// Our buttons, not the arrows that scroll bars and tab strips make for themselves.
    private static boolean ours(AbstractButton b) {
        String cls = b.getClass().getName();
        return cls.startsWith("io.github.logdelta") || cls.matches("javax\\.swing\\.J\\w+");
    }

    /// Buttons whose text is empty or a symbol and which have neither an accessible name nor a tooltip.
    private static void unnamedButtons(Container c, List<String> out) {
        for (Component child : c.getComponents()) {
            if (child instanceof AbstractButton b && ours(b)) {
                String text = b.getText() == null ? "" : b.getText();
                boolean words = text.codePoints().anyMatch(Character::isLetter);
                String name = b.getAccessibleContext().getAccessibleName();
                boolean named = (name != null && name.codePoints().anyMatch(Character::isLetter))
                        || b.getToolTipText() != null;
                if (!words && !named) {
                    out.add(b.getClass().getSimpleName() + "('" + text + "')");
                }
            }
            if (child instanceof Container sub) {
                unnamedButtons(sub, out);
            }
        }
    }
}
