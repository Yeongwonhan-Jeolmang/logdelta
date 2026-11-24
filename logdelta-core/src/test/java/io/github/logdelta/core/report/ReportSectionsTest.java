// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.report;

import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.engine.LogDeltaEngine;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.util.CancellationToken;
import io.github.logdelta.core.util.Json;
import io.github.logdelta.core.util.ProgressListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The report sections added in 2.0: format detection, templates, flaky lines, the problem
/// timeline, field changes and redaction notes.
class ReportSectionsTest {

    private static DiffResult result() {
        List<String> good = new ArrayList<>();
        List<String> good2 = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String ts = "2026-01-01T10:00:0" + i + "Z";
            good.add("{\"ts\":\"" + ts + "\",\"level\":\"info\",\"msg\":\"tick\",\"n\":" + i + ",\"run\":\"a\"}");
            good2.add("{\"ts\":\"" + ts + "\",\"level\":\"info\",\"msg\":\"tick\",\"n\":" + i + ",\"run\":\"b\"}");
            bad.add("{\"ts\":\"" + ts + "\",\"level\":\"info\",\"msg\":\"tick\",\"n\":" + (i * 10) + ",\"run\":\"a\"}");
            bad.add("{\"ts\":\"" + ts + "\",\"level\":\"error\",\"msg\":\"Retry " + i + " for order " + (40 + i)
                    + " failed\",\"token\":\"Bearer abcdefghijklmnop\"}");
        }
        return new LogDeltaEngine().compare(LogFile.ofLines("good", good), List.of(LogFile.ofLines("good2", good2)),
                LogFile.ofLines("bad", bad), DiffOptions.defaults(), ProgressListener.NONE, CancellationToken.NEVER);
    }

    @Test
    @DisplayName("JSON has the new sections and stays valid")
    void json() {
        DiffResult r = result();
        DiffResult red = r.redacted(r.options().redactor());
        Object doc = Json.parse(Reports.writer(ReportFormat.JSON).render(red, ReportOptions.DEFAULT));
        Map<?, ?> m = (Map<?, ?>) doc;
        assertEquals("json", ((Map<?, ?>) m.get("detected")).get("format"));
        Map<?, ?> insights = (Map<?, ?>) m.get("insights");
        assertFalse(((List<?>) insights.get("templateShifts")).isEmpty());
        assertFalse(((List<?>) insights.get("flakyLines")).isEmpty());
        assertTrue(((Long) m.get("redactedSecrets")) > 0);
        assertTrue(m.containsKey("problemTimeline"));
        Map<?, ?> options = (Map<?, ?>) m.get("options");
        assertEquals("auto", options.get("logFormat"));
        String text = Reports.writer(ReportFormat.JSON).render(red, ReportOptions.DEFAULT);
        assertTrue(text.contains("\"fieldChanges\""));
        assertFalse(text.contains("abcdefghijklmnop"));
    }

    @Test
    @DisplayName("Markdown and HTML show templates, flaky lines, the timeline and field changes")
    void markdownAndHtml() {
        DiffResult r = result();
        String md = Reports.writer(ReportFormat.MARKDOWN).render(r, ReportOptions.DEFAULT);
        assertTrue(md.contains("**Format:** JSON lines (detected)"));
        assertTrue(md.contains("## Message templates"));
        assertTrue(md.contains("Retry <*> for order <*> failed"));
        assertTrue(md.contains("## Flaky lines"));
        assertTrue(md.contains("## Warnings and errors"));
        assertTrue(md.contains("@@ n: 1 → 10 @@"));
        String html = Reports.writer(ReportFormat.HTML).render(r, ReportOptions.DEFAULT);
        assertTrue(html.contains("<svg class=\"tl\""));
        assertTrue(html.contains("Message templates"));
        assertTrue(html.contains("Changed fields: n: 1"));
        assertTrue(html.contains("Log format: JSON lines (detected)"));
    }
}
