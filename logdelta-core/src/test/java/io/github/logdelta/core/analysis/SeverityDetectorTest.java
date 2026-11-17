// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeverityDetectorTest {

    @Test
    @DisplayName("recognises level keywords")
    void keywords() {
        assertEquals(Severity.ERROR, SeverityDetector.detect("2026-01-01 ERROR [main] boom"));
        assertEquals(Severity.WARN, SeverityDetector.detect("WARNING: disk almost full"));
        assertEquals(Severity.ERROR, SeverityDetector.detect("SEVERE: java.util.logging style"));
        assertEquals(Severity.FATAL, SeverityDetector.detect("[CRITICAL] out of memory"));
        assertEquals(Severity.INFO, SeverityDetector.detect("INFO started"));
        assertEquals(Severity.DEBUG, SeverityDetector.detect("FINEST: details"));
    }

    @Test
    @DisplayName("recognises structured level fields")
    void fields() {
        assertEquals(Severity.ERROR, SeverityDetector.detect("ts=1 level=error msg=\"boom\""));
        assertEquals(Severity.WARN, SeverityDetector.detect("{\"level\":\"warn\",\"msg\":\"slow\"}"));
    }

    @Test
    @DisplayName("treats stack traces and exceptions as errors")
    void exceptions() {
        assertEquals(Severity.ERROR, SeverityDetector.detect("\tat com.acme.Foo.bar(Foo.java:12)"));
        assertEquals(Severity.ERROR, SeverityDetector.detect("Caused by: java.io.IOException: nope"));
        assertEquals(Severity.ERROR, SeverityDetector.detect("java.lang.NullPointerException"));
        assertEquals(Severity.ERROR, SeverityDetector.detect("Traceback (most recent call last):"));
    }

    @Test
    @DisplayName("ordinary words are not mistaken for levels")
    void noFalsePositives() {
        assertEquals(Severity.NONE, SeverityDetector.detect("No Error was found in the information"));
        assertEquals(Severity.NONE, SeverityDetector.detect("errors=0 warnings=0"));
        assertTrue(Severity.WARN.isProblem());
        assertFalse(Severity.INFO.isProblem());
    }
}
