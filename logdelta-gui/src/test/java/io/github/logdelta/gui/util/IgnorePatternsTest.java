// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.util;

import io.github.logdelta.core.model.ProcessedLine;
import io.github.logdelta.core.normalize.NormalizedText;
import io.github.logdelta.core.normalize.Normalizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IgnorePatternsTest {

    @Test
    @DisplayName("'ignore lines like this' builds a pattern that matches other runs of the same line")
    void suggestPattern() {
        Normalizer n = Normalizer.defaults();
        String line = "2026-11-12 08:49:03 DEBUG heartbeat ok (took 3ms) [a+b]";
        NormalizedText t = n.normalize(line);
        ProcessedLine pl = new ProcessedLine(1, line, t.normalized(), t.masks(), n.key(t));
        Pattern p = Pattern.compile(IgnorePatterns.suggest(pl));
        assertTrue(p.matcher(line).find());
        assertTrue(p.matcher("2027-01-01 00:00:00 DEBUG heartbeat ok (took 950ms) [a+b]").find());
        assertFalse(p.matcher("2027-01-01 00:00:00 DEBUG heartbeat failed").find());
    }
}
