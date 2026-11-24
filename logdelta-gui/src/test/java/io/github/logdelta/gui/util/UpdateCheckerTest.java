// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The update check's parsing; no network access.
class UpdateCheckerTest {

    @Test
    @DisplayName("versions compare numerically and tags lose their prefix")
    void versions() {
        assertEquals("2.0.0", UpdateChecker.version("v2.0.0"));
        assertEquals("1.1.0", UpdateChecker.version("v.1.1.0"));
        assertTrue(UpdateChecker.compare("2.10.0", "2.9.1") > 0);
        assertEquals(0, UpdateChecker.compare("2.0", "2.0.0"));
        assertTrue(UpdateChecker.compare("1.1.0", "v2.0.0") < 0);
    }

    @Test
    @DisplayName("a release document says whether it is newer")
    void parse() throws IOException {
        String json = "{\"tag_name\":\"v2.1.0\",\"name\":\"LogDelta 2.1\",\"html_url\":\"https://x/r\",\"assets\":[]}";
        UpdateChecker.Result r = UpdateChecker.parse(json, "2.0.0");
        assertTrue(r.newer());
        assertEquals("2.1.0", r.latestVersion());
        assertEquals("LogDelta 2.1", r.name());
        assertEquals("https://x/r", r.url());
        assertFalse(UpdateChecker.parse(json, "2.1.0").newer());
        assertEquals("v3", UpdateChecker.parse("{\"tag_name\":\"v3\",\"name\":\"\"}", "2.0.0").name());
        assertThrows(IOException.class, () -> UpdateChecker.parse("{}", "2.0.0"));
        assertThrows(IOException.class, () -> UpdateChecker.parse("<html>", "2.0.0"));
    }
}
