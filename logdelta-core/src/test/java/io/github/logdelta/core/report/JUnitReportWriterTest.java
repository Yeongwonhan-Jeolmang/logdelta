// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.report;

import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.engine.LogDeltaEngine;
import io.github.logdelta.core.io.LogFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JUnitReportWriterTest {

    private static Document parse(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("each new problem is a failing test case; output is well-formed even with control characters")
    void failures() throws Exception {
        DiffResult r = new LogDeltaEngine().compare(
                LogFile.ofLines("good.log", List.of("INFO start", "INFO a", "INFO b", "INFO c", "INFO stop")),
                LogFile.ofLines("bad.log", List.of("INFO start", "ERROR <db> & \u0001 down", "INFO a", "INFO b",
                        "INFO c", "WARN slow \"disk\"", "INFO stop")),
                DiffOptions.defaults());
        String xml = Reports.writer(ReportFormat.JUNIT).render(r, ReportOptions.DEFAULT);
        Document doc = parse(xml);
        Element root = doc.getDocumentElement();
        assertEquals("testsuites", root.getTagName());
        assertEquals("2", root.getAttribute("failures"));
        assertEquals(2, doc.getElementsByTagName("failure").getLength());
        assertEquals("ERROR at line 2: <db> & � down",
                ((Element) doc.getElementsByTagName("testcase").item(0)).getAttribute("name"));
    }

    @Test
    @DisplayName("identical logs produce one passing test case")
    void passing() throws Exception {
        LogFile f = LogFile.ofLines("a.log", List.of("INFO x"));
        DiffResult r = new LogDeltaEngine().compare(f, f, DiffOptions.defaults());
        Document doc = parse(Reports.writer(ReportFormat.JUNIT).render(r, ReportOptions.DEFAULT));
        assertEquals("0", doc.getDocumentElement().getAttribute("failures"));
        assertEquals("no new problems",
                ((Element) doc.getElementsByTagName("testcase").item(0)).getAttribute("name"));
    }
}
