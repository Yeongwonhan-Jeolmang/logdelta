// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Settings added in 2.0, stored in a throw-away preferences node.
class AppSettingsTest {

    @Test
    @DisplayName("new settings have safe defaults and round-trip")
    void roundTrip() throws Exception {
        Preferences node = Preferences.userRoot().node("io/github/logdelta/test-" + System.nanoTime());
        Path dir = Files.createTempDirectory("logdelta-settings");
        try {
            AppSettings s = new AppSettings(node, dir);
            assertFalse(s.colorBlindSafe());
            assertFalse(s.checkForUpdates(), "the update check is opt-in");
            assertTrue(s.redactExports());
            assertTrue(s.openTabs().isEmpty());
            assertEquals(dir.resolve("open-tabs"), s.openTabsDir());

            s.setColorBlindSafe(true);
            s.setLanguage("ko");
            s.setRestoreTabs(false);
            s.setCheckForUpdates(true);
            s.setLastUpdateCheck(1234L);
            s.setRedactExports(false);
            s.setOpenTabs(List.of("a.ldsession", "b.ldsession"), 1);

            AppSettings again = new AppSettings(node, dir);
            assertTrue(again.colorBlindSafe());
            assertEquals("ko", again.language());
            assertFalse(again.restoreTabs());
            assertTrue(again.checkForUpdates());
            assertEquals(1234L, again.lastUpdateCheck());
            assertFalse(again.redactExports());
            assertEquals(List.of("a.ldsession", "b.ldsession"), again.openTabs());
            assertEquals(1, again.selectedTab());
        } finally {
            node.removeNode();
        }
    }
}
