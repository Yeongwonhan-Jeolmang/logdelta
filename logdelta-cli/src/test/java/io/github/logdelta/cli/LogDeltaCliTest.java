// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// End-to-end tests that run the CLI against temporary files.
class LogDeltaCliTest {

    private Path dir;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("logdelta-cli");
        Files.writeString(dir.resolve("good.log"), """
                2026-01-01 10:00:00 INFO start id=3f2b8c1e-9a4d-4e7b-8c2a-1d5e6f7a8b9c
                2026-01-01 10:00:01 INFO order 1 placed
                2026-01-01 10:00:02 INFO heartbeat
                2026-01-01 10:00:03 INFO stop
                """);
        Files.writeString(dir.resolve("same.log"), """
                2026-02-02 11:11:11 INFO start id=0a7a4223-ca3b-4cbb-a682-bfdf96f72015
                2026-02-02 11:11:12 INFO order 1 placed
                2026-02-02 11:11:13 INFO heartbeat
                2026-02-02 11:11:14 INFO stop
                """);
        Files.writeString(dir.resolve("bad.log"), """
                2026-02-02 11:11:11 INFO start id=0a7a4223-ca3b-4cbb-a682-bfdf96f72015
                2026-02-02 11:11:12 ERROR order 1 rejected
                2026-02-02 11:11:14 INFO stop
                """);
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
    }

    private int run(String... args) {
        CliContext ctx = new CliContext(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), new ByteArrayInputStream(new byte[0]),
                Map.of(), false, dir, true);
        return new LogDeltaCli().run(args, ctx);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("exit status follows diff(1): 0 identical, 1 different, 2 error")
    void exitCodes() {
        assertEquals(0, run("-q", "good.log", "same.log"));
        assertEquals(1, run("-q", "good.log", "bad.log"));
        assertEquals(2, run("good.log", "missing.log"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("File not found: missing.log"));
        assertEquals(2, run("only-one.log"));
    }

    @Test
    @DisplayName("--exit-code problems only fails on new warnings or errors")
    void exitOnProblems() {
        assertEquals(1, run("-q", "--exit-code", "problems", "good.log", "bad.log"));
        assertEquals(0, run("-q", "--exit-code", "problems", "-I", "ERROR", "good.log", "bad.log"));
        assertEquals(0, run("-q", "--exit-code", "never", "good.log", "bad.log"));
    }

    @Test
    @DisplayName("unified output is the default when not on a terminal")
    void unifiedDefault() {
        run("good.log", "bad.log");
        String o = out();
        assertTrue(o.startsWith("--- "), o);
        assertTrue(o.contains("+2026-02-02 11:11:12 ERROR order 1 rejected"), o);
    }

    @Test
    @DisplayName("side-by-side and summary formats render")
    void terminalFormats() {
        run("-f", "side-by-side", "--width", "120", "good.log", "bad.log");
        assertTrue(out().contains("good.log"));
        assertTrue(out().contains("│"));
        out.reset();
        run("-f", "summary", "good.log", "bad.log");
        assertTrue(out().contains("New problems (1)"), out());
        assertFalse(out().contains("\u001b["), "no colour when not a terminal");
    }

    @Test
    @DisplayName("reports are written to files and the format follows the extension")
    void reportFiles() throws IOException {
        assertEquals(1, run("-o", "report.json", "good.log", "bad.log"));
        String json = Files.readString(dir.resolve("report.json"));
        assertTrue(json.contains("\"schemaVersion\""));
        run("-o", "out/report.html", "good.log", "bad.log");
        assertTrue(Files.readString(dir.resolve("out/report.html")).startsWith("<!DOCTYPE html>"));
    }

    @Test
    @DisplayName("profiles, masks and rule toggles are applied")
    void rulesAndProfiles() throws IOException {
        // Without the uuid rule the first lines differ.
        assertEquals(1, run("-q", "--disable", "uuid", "good.log", "same.log"));
        Files.writeString(dir.resolve("p.ldprofile"), "[ignore]\npattern = order\npattern = heartbeat\n");
        assertEquals(1, run("-q", "good.log", "bad.log"));
        assertEquals(0, run("-q", "-p", "p.ldprofile", "good.log", "bad.log"), "only ignored lines differ");
        assertEquals(2, run("-q", "--disable", "no-such-rule", "good.log", "bad.log"));
        assertEquals(2, run("-q", "-I", "[", "good.log", "bad.log"));
    }

    @Test
    @DisplayName("normalize, explain and rules sub-commands")
    void subCommands() throws IOException {
        assertEquals(0, run("normalize", "-n", "good.log"));
        assertTrue(out().startsWith("1\t<TIMESTAMP> INFO start id=<UUID>"), out());
        out.reset();
        assertEquals(0, run("explain", "--mask", "order \\d+=>order <N>", "INFO order 42 placed"));
        assertTrue(out().contains("mask-1"), out());
        assertTrue(out().contains("= INFO order <N> placed"), out());
        out.reset();
        assertEquals(0, run("rules"));
        assertTrue(out().contains("iso-timestamp"));
        assertEquals(0, run("rules", "--disable", "epoch", "--export", "team.ldprofile"));
        assertTrue(Files.readString(dir.resolve("team.ldprofile")).contains("[rule epoch]\nenabled = false"));
    }

    @Test
    @DisplayName("help and version")
    void helpAndVersion() {
        assertEquals(0, run("--version"));
        assertTrue(out().startsWith("LogDelta "));
        out.reset();
        assertEquals(0, run("help", "diff"));
        assertTrue(out().contains("--ignore REGEX"));
        assertEquals(2, run());
    }
}
