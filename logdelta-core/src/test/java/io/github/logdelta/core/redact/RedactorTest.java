// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.redact;

import io.github.logdelta.core.diff.Span;
import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.engine.LogDeltaEngine;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.normalize.Mask;
import io.github.logdelta.core.report.ReportFormat;
import io.github.logdelta.core.report.ReportOptions;
import io.github.logdelta.core.report.Reports;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactorTest {

    private static final Redactor R = Redactor.defaults();

    @Test
    @DisplayName("secrets are replaced, keeping the key")
    void detectors() {
        assertEquals("Authorization: Bearer [REDACTED]", R.apply("Authorization: Bearer abc.def-123456"));
        assertEquals("password=[REDACTED] user=bob", R.apply("password=hunter2 user=bob"));
        assertEquals("{\"api_key\":\"[REDACTED]\"}", R.apply("{\"api_key\":\"sk_live_abcdefghijklmnop\"}"));
        assertEquals("https://[REDACTED]@db.example.com/x", R.apply("https://admin:s3cret@db.example.com/x"));
        assertEquals("mail [REDACTED] now", R.apply("mail jane.doe+ci@example.co.uk now"));
        assertEquals("key [REDACTED]", R.apply("key AKIAIOSFODNN7EXAMPLE"));
        assertEquals("token [REDACTED]", R.apply("token ghp_" + "a".repeat(36)));
        assertEquals("jwt [REDACTED]", R.apply("jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.abcdefghijk"));
        assertEquals("tokens: 5 errors: 0", R.apply("tokens: 5 errors: 0"));
        assertEquals("com.example.Order@1b6d3586", R.apply("com.example.Order@1b6d3586"));
        assertSame("plain", R.apply("plain"));
    }

    @Test
    @DisplayName("custom patterns redact the whole match or the 'secret' group")
    void custom() {
        Redactor r = Redactor.none().withPatterns(List.of("customer-\\d+", "pin (?<secret>\\d{4})"));
        assertEquals("[REDACTED] pin [REDACTED]", r.apply("customer-42 pin 1234"));
        assertTrue(Redactor.none().isNone());
        assertFalse(R.isNone());
    }

    @Test
    @DisplayName("offsets after a secret move with the shorter or longer text")
    void mapping() {
        Redaction red = R.redact("pwd=abcdefghijklmnop then ERROR");
        String text = red.text();
        assertEquals("pwd=[REDACTED] then ERROR", text);
        int error = "pwd=abcdefghijklmnop then ".length();
        List<Span> spans = red.mapSpans(List.of(new Span(error, error + 5)));
        assertEquals("ERROR", text.substring(spans.getFirst().start(), spans.getFirst().end()));
        List<Mask> masks = red.mapMasks(List.of(new Mask(error, error + 5, "<L>", "lvl"), new Mask(4, 8, "<X>", "x")));
        assertEquals(1, masks.size(), "a mask inside the secret is dropped");
        assertEquals("ERROR", text.substring(masks.getFirst().start(), masks.getFirst().end()));
        assertEquals(1, red.count());
    }

    @Test
    @DisplayName("a redacted result keeps highlights aligned and reports contain no secret")
    void result() {
        LogFile a = LogFile.ofLines("a", List.of("login user=bob password=hunter2 status=OK"));
        LogFile b = LogFile.ofLines("b", List.of("login user=bob password=hunter2 status=FAILED"));
        DiffResult r = new LogDeltaEngine().compare(a, b, DiffOptions.defaults());
        DiffResult red = r.redacted(r.options().redactor());
        assertEquals(2, red.redactedSecrets());
        DiffRow row = red.rows().getFirst();
        String right = red.right(row).text();
        Span s = row.inline().right().getFirst();
        assertEquals("FAILED", right.substring(s.start(), s.end()));
        for (ReportFormat f : ReportFormat.values()) {
            String report = Reports.writer(f).render(red, ReportOptions.DEFAULT);
            assertFalse(report.contains("hunter2"), f.name());
        }
        assertSame(r, r.redacted(Redactor.none()));
        DiffOptions off = DiffOptions.builder().redactSecrets(false).build();
        assertTrue(off.redactor().isNone());
    }

    @Test
    @DisplayName("quoted values are removed whole; placeholders and git remotes are left alone")
    void edges() {
        Redactor r = Redactor.defaults();
        assertEquals("passwd=\"[REDACTED]\" x", r.apply("passwd=\"a b c\" x"));
        assertEquals("token='[REDACTED]' c", r.apply("token='a b' c"));
        assertEquals("{\"msg\":\"login password=\\\"[REDACTED]\\\" ok\"}",
                r.apply("{\"msg\":\"login password=\\\"abc def\\\" ok\"}"));
        assertEquals("password=<UUID> x", r.apply("password=<UUID> x"), "already masked by a rule");
        assertEquals("git@github.com:org/repo.git", r.apply("git@github.com:org/repo.git"));
        assertEquals("mailto:[REDACTED]", r.apply("mailto:bob@example.org"));
    }

    @Test
    @DisplayName("reports don't leak secrets through field changes or flaky-line patterns")
    void noLeaks() {
        DiffResult structured = new LogDeltaEngine().compare(
                LogFile.ofLines("a", List.of("{\"msg\":\"login\",\"password\":\"hunter2\",\"n\":1}")),
                LogFile.ofLines("b", List.of("{\"msg\":\"login\",\"password\":\"hunter9\",\"n\":2}")),
                DiffOptions.defaults());
        DiffResult red = structured.redacted(Redactor.defaults());
        DiffRow row = red.rows().getFirst();
        List<String> changes = red.fieldChanges(row).stream().map(c -> c.describe()).toList();
        assertEquals(List.of("password: [REDACTED] → [REDACTED]", "n: 1 → 2"), changes,
                "the change is still reported, without the values");
        for (ReportFormat f : ReportFormat.values()) {
            String out = Reports.writer(f).render(red, ReportOptions.DEFAULT);
            assertFalse(out.contains("hunter"), f.name());
        }

        LogFile good = LogFile.ofLines("good", List.of("start", "login password=hunter2 ok", "end"));
        LogFile good2 = LogFile.ofLines("good2", List.of("start", "end"));
        LogFile bad = LogFile.ofLines("bad", List.of("start", "end", "ERROR x"));
        DiffResult flaky = new LogDeltaEngine().compare(good, List.of(good2), bad, DiffOptions.defaults(),
                io.github.logdelta.core.util.ProgressListener.NONE, io.github.logdelta.core.util.CancellationToken.NEVER);
        String pattern = flaky.redacted(Redactor.defaults()).insights().flakyLines().getFirst().ignorePattern();
        assertFalse(pattern.contains("hunter2"), pattern);
        assertTrue(java.util.regex.Pattern.compile(pattern).matcher("login password=hunter2 ok").find(),
                "the redacted pattern still matches the line");
    }
}
