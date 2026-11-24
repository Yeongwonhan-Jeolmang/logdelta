// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.structured;

import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.engine.LogDeltaEngine;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.RowKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredCompareTest {

    private static DiffResult compare(List<String> a, List<String> b, DiffOptions o) {
        return new LogDeltaEngine().compare(LogFile.ofLines("a", a), LogFile.ofLines("b", b), o);
    }

    @Test
    @DisplayName("JSON lines: field order and ignored fields do not count")
    void orderAndIgnoredFields() {
        DiffResult r = compare(
                List.of("{\"ts\":\"2026-01-01T10:00:00Z\",\"level\":\"info\",\"msg\":\"start\",\"traceId\":\"abc\"}"),
                List.of("{\"msg\":\"start\",\"traceId\":\"zzz\",\"level\":\"info\",\"ts\":\"2026-02-02T11:11:11Z\"}"),
                DiffOptions.defaults());
        assertEquals(LogFormat.JSON, r.detection().format());
        assertTrue(r.detection().formatDetected());
        assertTrue(r.isIdentical(), "reordered fields with different ts/traceId are equal");
        assertTrue(r.leftLines().getFirst().isStructured());
        assertTrue(r.leftLines().getFirst().normalized().contains(StructuredLines.IGNORED));
    }

    @Test
    @DisplayName("modified JSON lines highlight and list the changed fields")
    void fieldChanges() {
        String l = "{\"msg\":\"order\",\"status\":200,\"cache\":\"hit\"}";
        String r = "{\"msg\":\"order\",\"status\":502,\"retry\":true}";
        DiffResult res = compare(List.of(l), List.of(r), DiffOptions.defaults());
        DiffRow row = res.rows().getFirst();
        assertEquals(RowKind.MODIFIED, row.kind());
        var changes = res.fieldChanges(row);
        assertEquals(List.of("status: 200 \u2192 502", "- cache: hit", "+ retry: true"),
                changes.stream().map(StructuredLines.FieldChange::describe).toList());
        var spans = row.inline().right();
        assertEquals("502", r.substring(spans.getFirst().start(), spans.getFirst().end()));
        assertEquals("\"retry\":true", r.substring(spans.get(1).start(), spans.get(1).end()));
    }

    @Test
    @DisplayName("rules still mask values inside fields; ignore-field and compare-field change the key")
    void rulesAndOptions() {
        DiffOptions o = DiffOptions.defaults();
        DiffResult r = compare(List.of("{\"msg\":\"took 12ms\",\"id\":\"3f2b8c1e-9a4d-4e7b-8c2a-1d5e6f7a8b9c\"}"),
                List.of("{\"msg\":\"took 99ms\",\"id\":\"0a7a4223-ca3b-4cbb-a682-bfdf96f72015\"}"), o);
        assertTrue(r.isIdentical());
        DiffResult strict = compare(List.of("{\"msg\":\"a\",\"ts\":1}"), List.of("{\"msg\":\"a\",\"ts\":2}"),
                o.toBuilder().ignoreFields(List.of()).build());
        assertFalse(strict.isIdentical());
        DiffResult loose = compare(List.of("{\"msg\":\"a\",\"user\":\"x\"}"), List.of("{\"msg\":\"a\",\"user\":\"y\"}"),
                o.toBuilder().addIgnoreField("user").build());
        assertTrue(loose.isIdentical());
    }

    @Test
    @DisplayName("logfmt is detected and compared field by field; plain text stays plain")
    void logfmtAndPlain() {
        DiffResult r = compare(List.of("level=info msg=\"cache warm\" entries=10 ts=1"),
                List.of("ts=2 msg=\"cache warm\" level=info entries=11"), DiffOptions.defaults());
        assertEquals(LogFormat.LOGFMT, r.detection().format());
        assertEquals(List.of("entries: 10 \u2192 11"),
                r.fieldChanges(r.rows().getFirst()).stream().map(StructuredLines.FieldChange::describe).toList());
        DiffResult forced = compare(List.of("{\"a\":1}"), List.of("{\"a\":1 }"),
                DiffOptions.builder().format(LogFormat.PLAIN).build());
        assertEquals(LogFormat.PLAIN, forced.detection().format());
        assertFalse(forced.leftLines().getFirst().isStructured());
    }

    @Test
    @DisplayName("structured lines take their time and level from fields")
    void timeAndLevel() {
        DiffResult r = compare(
                List.of("{\"time\":\"2026-01-01T10:00:00.000Z\",\"level\":\"info\",\"msg\":\"a\"}",
                        "{\"time\":\"2026-01-01T10:00:05.000Z\",\"level\":\"info\",\"msg\":\"b\"}"),
                List.of("{\"time\":\"2026-01-01T10:00:00.000Z\",\"level\":\"info\",\"msg\":\"a\"}",
                        "{\"time\":\"2026-01-01T10:00:09.000Z\",\"level\":50,\"msg\":\"boom\"}"),
                DiffOptions.defaults());
        assertTrue(r.timing().available());
        assertEquals(9000, r.timing().right().duration());
        assertEquals(1, r.insights().newProblems().size(), "pino level 50 is an error");
    }
}
