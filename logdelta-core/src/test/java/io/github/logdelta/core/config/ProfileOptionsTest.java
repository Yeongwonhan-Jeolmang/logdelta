// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.config;

import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.structured.LogFormat;
import io.github.logdelta.core.timing.TimeWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Profile options added in 2.0.
class ProfileOptionsTest {

    @Test
    @DisplayName("format, fields, preset, window and redaction round-trip through a profile")
    void roundTrip() throws Exception {
        DiffOptions o = ProfileIO.parse("""
                [options]
                log-format = json
                ignore-fields = ts, request.*, userId
                preset = none
                since = +1m
                until = 08:55
                redact = false

                [redact]
                pattern = customer-\\d+
                """);
        assertEquals(LogFormat.JSON, o.format());
        assertEquals(List.of("ts", "request.*", "userId"), o.ignoreFields());
        assertEquals("none", o.preset());
        assertInstanceOf(TimeWindow.Relative.class, o.timeWindow().since());
        assertInstanceOf(TimeWindow.TimeOfDay.class, o.timeWindow().until());
        assertFalse(o.redactSecrets());
        assertEquals(List.of("customer-\\d+"), o.redactPatterns());
        DiffOptions again = ProfileIO.parse(ProfileIO.format(o));
        assertEquals(o, again);
    }

    @Test
    @DisplayName("defaults are not written out and invalid values are reported")
    void defaultsAndErrors() {
        String text = ProfileIO.format(DiffOptions.defaults());
        assertFalse(text.contains("ignore-fields"));
        assertTrue(text.contains("preset = auto"));
        assertTrue(text.contains("redact = true"));
        assertThrows(ProfileParseException.class, () -> ProfileIO.parse("[options]\nsince = tomorrow\n"));
        assertThrows(ProfileParseException.class, () -> ProfileIO.parse("[options]\nlog-format = yaml\n"));
        assertThrows(ProfileParseException.class, () -> ProfileIO.parse("[options]\npreset = cobol\n"));
        assertThrows(ProfileParseException.class, () -> ProfileIO.parse("[redact]\nregex = x\n"));
        assertThrows(ProfileParseException.class, () -> ProfileIO.parse("[redact]\npattern = [\n"));
    }
}
