// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.report;

import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.engine.LogDeltaEngine;
import io.github.logdelta.core.io.LogFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportWritersTest {

    private static DiffResult sample() {
        return new LogDeltaEngine().compare(
                LogFile.ofLines("good.log", List.of("INFO start", "INFO payment accepted for <b>order</b>", "INFO a", "INFO b",
                        "INFO stop")),
                LogFile.ofLines("bad.log", List.of("INFO start", "ERROR payment rejected for <b>order</b> \"x\"", "INFO a",
                        "INFO b", "INFO stop")),
                DiffOptions.defaults());
    }

    @Test
    @DisplayName("unified diff has headers, hunk ranges and markers")
    void unified() {
        String out = Reports.writer(ReportFormat.UNIFIED).render(sample(), ReportOptions.DEFAULT.withContext(1));
        assertEquals("""
                --- good.log
                +++ bad.log
                @@ -1,3 +1,3 @@
                 INFO start
                -INFO payment accepted for <b>order</b>
                +ERROR payment rejected for <b>order</b> "x"
                 INFO a
                """, out);
    }

    @Test
    @DisplayName("identical inputs produce an empty unified diff")
    void unifiedIdentical() {
        DiffResult r = new LogDeltaEngine().compare(LogFile.ofLines("a", List.of("x")), LogFile.ofLines("b",
                List.of("x")), DiffOptions.defaults());
        assertEquals("", Reports.writer(ReportFormat.UNIFIED).render(r, ReportOptions.DEFAULT));
    }

    @Test
    @DisplayName("HTML escapes log content and is self-contained")
    void html() {
        String out = Reports.writer(ReportFormat.HTML).render(sample(), ReportOptions.DEFAULT);
        assertTrue(out.startsWith("<!DOCTYPE html>"));
        assertTrue(out.contains("&lt;b&gt;order&lt;/b&gt;"));
        assertFalse(out.contains("<b>order</b>"));
        assertTrue(out.contains("<mark>"), "inline highlights are rendered");
        assertFalse(out.contains("<link"), "no external stylesheets");
        assertFalse(out.contains("src=\"http"), "no external scripts");
        assertTrue(out.contains("New problems"));
    }

    @Test
    @DisplayName("JSON is well-formed and contains the key sections")
    void json() {
        String out = Reports.writer(ReportFormat.JSON).render(sample(), ReportOptions.DEFAULT);
        for (String key : List.of("\"schemaVersion\": 1", "\"stats\"", "\"insights\"", "\"hunks\"",
                "\"kind\": \"modified\"", "\"text\": \"ERROR payment rejected for <b>order</b> \\\"x\\\"\"", "\"changed\"")) {
            assertTrue(out.contains(key), key + " missing in\n" + out);
        }
        assertEquals(count(out, '{'), count(out, '}'));
        assertEquals(count(out, '['), count(out, ']'));
    }

    @Test
    @DisplayName("Markdown summary lists new problems")
    void markdown() {
        String out = Reports.writer(ReportFormat.MARKDOWN).render(sample(), ReportOptions.DEFAULT);
        assertTrue(out.startsWith("# Log comparison"));
        assertTrue(out.contains("## New problems (1)"));
        assertTrue(out.contains("```diff"));
    }

    @Test
    @DisplayName("reports can be written to files in nested directories")
    void writeFile() throws Exception {
        Path dir = Files.createTempDirectory("logdelta");
        Path file = dir.resolve("nested/report.html");
        Reports.writeFile(sample(), ReportFormat.HTML, ReportOptions.DEFAULT, file);
        assertTrue(Files.size(file) > 1000);
        Files.delete(file);
        Files.delete(file.getParent());
        Files.delete(dir);
    }

    @Test
    @DisplayName("format names parse leniently")
    void parseFormat() {
        assertEquals(ReportFormat.MARKDOWN, ReportFormat.parse("md"));
        assertEquals(ReportFormat.UNIFIED, ReportFormat.parse("diff"));
    }

    private static long count(String s, char c) {
        // Braces inside JSON strings would skew this; the sample deliberately contains none.
        return s.chars().filter(ch -> ch == c).count();
    }
}
