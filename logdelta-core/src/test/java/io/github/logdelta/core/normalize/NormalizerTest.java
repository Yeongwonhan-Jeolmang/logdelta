// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalizerTest {

    private final Normalizer defaults = Normalizer.defaults();

    private String norm(String line) {
        return defaults.normalize(line).normalized();
    }

    @Test
    @DisplayName("two runs of the same log line normalize to the same text")
    void typicalLine() {
        String a = "2026-11-10 09:14:02.021 INFO [pool-1-thread-3] Request 3f2b8c1e-9a4d-4e7b-8c2a-1d5e6f7a8b9c "
                + "from 10.0.3.19:53143 took 132ms";
        String b = "2026-11-11 22:41:37.020 INFO [pool-1-thread-7] Request 0a7a4223-ca3b-4cbb-a682-bfdf96f72015 "
                + "from 10.0.4.2:61002 took 9ms";
        assertEquals(norm(a), norm(b));
        assertEquals("<TIMESTAMP> INFO [pool-1-thread-<N>] Request <UUID> from <IP>:<PORT> took <DURATION>", norm(a));
    }

    @Test
    @DisplayName("built-in rules cover common formats")
    void builtins() {
        assertEquals("at <TIMESTAMP> ok", norm("at 2026-11-12T08:49:03.123Z ok"));
        assertEquals("<TIMESTAMP> host sshd[<PID>]: hi", norm("Nov 12 08:49:03 host sshd[1234]: hi"));
        assertEquals("[<TIMESTAMP>] GET", norm("[12/Nov/2026:08:49:03 -0500] GET"));
        assertEquals("on <DATE> at <TIME>", norm("on 11/12/2026 at 8:49:03"));
        assertEquals("ts=<EPOCH>", norm("ts=1762955343123"));
        assertEquals("obj com.x.Order@<ID> at <ADDR>", norm("obj com.x.Order@1b6d3586 at 0x7ffd5e8c3a10"));
        assertEquals("sha <HASH>", norm("sha da39a3ee5e6b4b0d3255bfef95601890afd80709"));
        assertEquals("container <HEX> up", norm("container 4bf92f3577b3 up"));
        assertEquals("pod checkout-<POD> ready", norm("pod checkout-7d9f8b6c5d-x2x7q ready"));
        assertEquals("traceId=<ID> pid=<N> TID: <N>", norm("traceId=4bf92f35-77b3 pid=4242 TID: 17"));
        assertEquals("wrote <TMPDIR>", norm("wrote /tmp/junit8812/out.txt"));
        assertEquals("ip <IP>", norm("ip 2001:0db8:0000:0000:0000:ff00:0042:8329"));
    }

    @Test
    @DisplayName("ordinary text, ports and versions are left alone")
    void noFalsePositives() {
        assertEquals("listening on port 8080", norm("listening on port 8080"));
        assertEquals("version 2.8.3 ready", norm("version 2.8.3 ready"));
        assertEquals("checkout-processing-retry", norm("checkout-processing-retry"));
        assertEquals("attempt 1/3", norm("attempt 1/3"));
        assertEquals("HikariPool-1 - Start completed.", norm("HikariPool-1 - Start completed."));
    }

    @Test
    @DisplayName("masks record original offsets and the rule that produced them")
    void maskOffsets() {
        String line = "id=3f2b8c1e-9a4d-4e7b-8c2a-1d5e6f7a8b9c done";
        NormalizedText n = defaults.normalize(line);
        assertEquals(1, n.masks().size());
        Mask m = n.masks().get(0);
        assertEquals("uuid", m.ruleId());
        assertEquals(3, m.start());
        assertEquals(39, m.end());
        assertEquals(m, n.maskAt(10));
        assertNull(n.maskAt(1));
    }

    @Test
    @DisplayName("later rules never match inside earlier masks")
    void rulesDoNotOverlap() {
        RuleSet rules = RuleSet.of(List.of(
                Rule.builder("word").pattern("abc").replacement("<W>").build(),
                Rule.builder("angle").pattern("<W>").replacement("X").build()));
        Normalizer n = new Normalizer(rules, false, false);
        assertEquals("<W> <W>", n.normalize("abc abc").normalized());
    }

    @Test
    @DisplayName("replacements can reference capture groups")
    void groups() {
        Rule r = Rule.builder("kv").pattern("(?<key>user)=(\\w+)").replacement("${key}=<$2>").build();
        Normalizer n = new Normalizer(RuleSet.of(List.of(r)), false, false);
        assertEquals("user=<alice> ok", n.normalize("user=alice ok").normalized());
        Rule literal = Rule.builder("cost").pattern("\\d+").replacement("\\$N").build();
        assertEquals("$N", new Normalizer(RuleSet.of(List.of(literal)), false, false).normalize("42").normalized());
    }

    @Test
    @DisplayName("comparison keys honour case and whitespace options")
    void keyOptions() {
        Normalizer n = new Normalizer(RuleSet.empty(), true, true);
        assertEquals("a b c", n.key("  A \t B   c "));
        Normalizer exact = new Normalizer(RuleSet.empty(), false, false);
        assertNotEquals(exact.key("a  b"), exact.key("a b"));
    }

    @Test
    @DisplayName("disabled rules are skipped")
    void disabled() {
        RuleSet rules = RuleSet.defaults().withEnabled("uuid", false);
        String line = "id 3f2b8c1e-9a4d-4e7b-8c2a-1d5e6f7a8b9c";
        assertFalse(new Normalizer(rules, false, false).normalize(line).normalized().contains("<UUID>"));
    }

    @Test
    @DisplayName("invalid rules are rejected with a helpful message")
    void invalidRules() {
        InvalidRuleException e = assertThrows(InvalidRuleException.class,
                () -> Rule.builder("bad").pattern("(unclosed").build());
        assertTrue(e.getMessage().contains("bad"));
        assertThrows(InvalidRuleException.class, () -> Rule.builder("Bad Id").pattern("x").build());
        assertThrows(InvalidRuleException.class, () -> Rule.builder("g").pattern("x").replacement("$1").build());
        assertThrows(InvalidRuleException.class, () -> RuleSet.of(List.of(
                Rule.builder("a").pattern("x").build(), Rule.builder("a").pattern("y").build())));
    }

    @Test
    @DisplayName("line filter matches anywhere in the line")
    void lineFilter() {
        LineFilter f = LineFilter.of(List.of("heartbeat", "^DEBUG"));
        assertTrue(f.isIgnored("12:00 heartbeat ok"));
        assertTrue(f.isIgnored("DEBUG x"));
        assertFalse(f.isIgnored("INFO DEBUG x"));
        assertThrows(InvalidRuleException.class, () -> LineFilter.of(List.of("[")));
    }
}
