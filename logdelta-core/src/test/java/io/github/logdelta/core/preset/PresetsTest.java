// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.preset;

import io.github.logdelta.core.analysis.Severity;
import io.github.logdelta.core.analysis.SeverityDetector;
import io.github.logdelta.core.config.ProfileIO;
import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.engine.LogDeltaEngine;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.normalize.Normalizer;
import io.github.logdelta.core.normalize.RuleSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetsTest {

    /// Two lines in each format that must normalize to the same key with the preset's rules.
    private static final List<String[]> SAMPLES = List.of(
            new String[] {"spring-boot",
                "2026-11-12T08:49:03.123Z  INFO 4242 --- [checkout] [nio-8080-exec-1] c.a.Checkout : Started App in 3.2 seconds (process running for 3.9)",
                "2026-11-13T09:00:00.001Z  INFO 77 --- [checkout] [nio-8080-exec-7] c.a.Checkout : Started App in 4.1 seconds (process running for 5.0)"},
            new String[] {"access-log",
                "10.0.0.1 - - [12/Nov/2026:08:49:03 -0500] \"GET /app.js?_=1762955343123 HTTP/1.1\" 200 5120 \"-\" \"curl\" 0.012",
                "10.0.0.9 - - [13/Nov/2026:09:00:00 -0500] \"GET /app.js?_=1762999999999 HTTP/1.1\" 200 4999 \"-\" \"curl\" 0.250"},
            new String[] {"kubernetes",
                "2026-11-12T08:49:03.123456789Z stdout F connected to ip-10-0-1-23.ec2.internal",
                "2026-11-13T09:00:00.000000001Z stdout P connected to ip-10-0-9-99.ec2.internal"},
            new String[] {"journald",
                "Nov 12 08:49:03 web-1 systemd[1]: Started Session 42 of User bob.",
                "Nov 13 09:00:00 web-7 systemd[1]: Started Session 77 of User bob."},
            new String[] {"logcat",
                "11-12 08:49:03.123  1234  5678 E PaymentClient: request failed",
                "11-13 09:00:00.000   999   888 E PaymentClient: request failed"},
            new String[] {"python",
                "2026-11-12 08:49:03,123 - worker - INFO - ForkPoolWorker-3 picked up Task-17",
                "2026-11-13 09:00:00,000 - worker - INFO - ForkPoolWorker-1 picked up Task-99"});

    @Test
    @DisplayName("each preset is detected from its own format")
    void detect() {
        for (String[] s : SAMPLES) {
            Preset p = Presets.detect(List.of(List.of(s[1], s[2])));
            assertNotNull(p, s[0]);
            assertEquals(s[0], p.id());
        }
        assertNull(Presets.detect(List.of(List.of("hello", "world"))));
        assertNull(Presets.detect(List.of(List.of("2026-01-01 12:00:00,123 INFO  com.foo.Bar:42 - msg",
                "2026-01-01 12:00:00,124 ERROR com.foo.Bar:42 - failed"))), "log4j is not Python");
        assertNull(Presets.detect(List.of(List.of("2026-01-01 12:00:00.123 INFO  [main] c.a.App - started",
                "2026-01-01 12:00:01.000 WARN  [main] c.a.App - slow"))), "logback has no preset");
    }

    @Test
    @DisplayName("each preset's rules make two runs of its format equal")
    void rules() {
        for (String[] s : SAMPLES) {
            Preset p = Presets.find(s[0]);
            Normalizer n = new Normalizer(Presets.apply(p, RuleSet.defaults()), false, false);
            assertEquals(n.key(s[1]), n.key(s[2]), s[0] + ": " + n.normalize(s[1]).normalized());
        }
    }

    @Test
    @DisplayName("the engine adds detected preset rules; 'none' and explicit ids work too")
    void engine() {
        String[] spring = SAMPLES.getFirst();
        LogFile a = LogFile.ofLines("a", List.of(spring[1]));
        LogFile b = LogFile.ofLines("b", List.of(spring[2]));
        DiffResult auto = new LogDeltaEngine().compare(a, b, DiffOptions.defaults());
        assertEquals("spring-boot", auto.detection().preset().id());
        assertTrue(auto.detection().presetDetected());
        assertTrue(auto.isIdentical());
        assertNotNull(auto.options().rules().find("spring-boot.pid"));
        DiffResult none = new LogDeltaEngine().compare(a, b, DiffOptions.builder().preset("none").build());
        assertNull(none.detection().preset());
        assertFalse(none.isIdentical());
        DiffResult forced = new LogDeltaEngine().compare(a, b, DiffOptions.builder().preset("spring-boot").build());
        assertFalse(forced.detection().presetDetected());
        assertTrue(forced.isIdentical());
        assertThrows(IllegalArgumentException.class, () -> DiffOptions.builder().preset("nope").build());
    }

    @Test
    @DisplayName("a profile can turn off a single preset rule")
    void profileOverride() throws Exception {
        DiffOptions o = ProfileIO.parse("[options]\npreset = spring-boot\n\n[rule spring-boot.pid]\nenabled = false\n");
        assertEquals("spring-boot", o.preset());
        assertFalse(o.rules().find("spring-boot.pid").enabled());
        RuleSet applied = Presets.apply(Presets.find("spring-boot"), o.rules());
        assertFalse(applied.find("spring-boot.pid").enabled(), "the disabled rule stays disabled");
        assertTrue(ProfileIO.format(o.toBuilder().rules(applied).build()).contains("[rule spring-boot.pid]\nenabled = false"));
    }

    @Test
    @DisplayName("logcat and klog severity letters are recognised")
    void severity() {
        assertEquals(Severity.ERROR, SeverityDetector.detect("11-12 08:49:03.123  1234  5678 E Tag: boom"));
        assertEquals(Severity.WARN, SeverityDetector.detect("W/Tag( 1234): careful"));
        assertEquals(Severity.FATAL, SeverityDetector.detect("F1112 08:49:03.123456   12345 main.go:12] dead"));
        assertEquals(Severity.INFO, SeverityDetector.detect("I1112 08:49:03.123456   12345 main.go:12] fine"));
    }
}
