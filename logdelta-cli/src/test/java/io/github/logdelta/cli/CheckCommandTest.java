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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// End-to-end tests for `check`, folder comparisons, extra baselines and sessions.
class CheckCommandTest {

    private Path dir;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private final Map<String, String> env = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("logdelta-check");
        write("good.log", "INFO start session=a1", "INFO cache warm", "INFO stop");
        write("good-2.log", "INFO start session=b2", "WARN cache slow", "INFO stop");
        write("flaky.log", "INFO start session=c3", "WARN cache slow", "INFO stop");
        write("bad.log", "INFO start session=d4", "ERROR cache down", "INFO stop");
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
    }

    private void write(String name, String... lines) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.write(p, List.of(lines));
    }

    private int run(String... args) {
        out.reset();
        err.reset();
        CliContext ctx = new CliContext(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), new ByteArrayInputStream(new byte[0]),
                env, false, dir, true);
        return new LogDeltaCli().run(args, ctx);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("check fails only on new problems; extra baselines explain normal variation")
    void newProblemsOnly() {
        assertEquals(1, run("check", "-q", "-b", "good.log", "flaky.log"));
        assertTrue(out().contains("FAIL"), out());
        assertEquals(0, run("check", "-q", "-b", "good.log", "-b", "good-2.log", "flaky.log"));
        assertTrue(out().contains("PASS"), out());
        assertEquals(1, run("check", "-q", "-b", "good.log", "-b", "good-2.log", "bad.log"));
        assertEquals(0, run("check", "-q", "--fail-on", "errors", "-b", "good.log", "flaky.log"));
        assertEquals(1, run("check", "-q", "--fail-on", "changes", "-b", "good.log", "-b", "good-2.log",
                "flaky.log"));
    }

    @Test
    @DisplayName("--update-baseline bootstraps and replaces; --update-on-pass only on success")
    void updates() throws IOException {
        assertEquals(2, run("check", "-b", "base/app.log", "good.log"));
        assertEquals(0, run("check", "-q", "-u", "-b", "base/app.log", "good.log"));
        assertTrue(Files.exists(dir.resolve("base/app.log")));

        assertEquals(1, run("check", "-q", "--update-on-pass", "-b", "base/app.log", "bad.log"));
        assertEquals(Files.readString(dir.resolve("good.log")), Files.readString(dir.resolve("base/app.log")));

        assertEquals(0, run("check", "-q", "-u", "-b", "base/app.log", "bad.log"));
        assertTrue(out().contains("accepted by --update-baseline"), out());
        assertEquals(Files.readString(dir.resolve("bad.log")), Files.readString(dir.resolve("base/app.log")));
    }

    @Test
    @DisplayName("a baseline folder collects accepted runs and keeps only the newest")
    void baselineDir() throws IOException {
        assertEquals(0, run("check", "-q", "--update-on-pass", "-B", "runs", "--keep", "2", "good.log"));
        assertEquals(0, run("check", "-q", "--update-on-pass", "-B", "runs", "--keep", "2", "good.log"));
        assertEquals(0, run("check", "-q", "--update-on-pass", "-B", "runs", "--keep", "2", "good.log"));
        try (var files = Files.list(dir.resolve("runs"))) {
            assertTrue(files.count() <= 2);
        }
    }

    @Test
    @DisplayName("reports, JUnit XML and GitHub annotations")
    void reportsAndGithub() throws IOException {
        Path summary = dir.resolve("step-summary.md");
        env.put("GITHUB_STEP_SUMMARY", summary.toString());
        assertEquals(1, run("check", "--github", "-q", "-b", "good.log", "--report", "out/r.xml",
                "--report", "markdown:out/r.txt", "bad.log"));
        assertTrue(out().contains("::error title=New ERROR in bad.log::line 2"), out());
        String xml = Files.readString(dir.resolve("out/r.xml"));
        assertTrue(xml.contains("<failure type=\"NewProblem\""), xml);
        assertTrue(Files.readString(dir.resolve("out/r.txt")).startsWith("# Log comparison"));
        assertTrue(Files.readString(summary).contains("# Log comparison"));
    }

    @Test
    @DisplayName("diff accepts several baselines and compares folders")
    void diffBaselinesAndFolders() throws IOException {
        assertEquals(1, run("-q", "--exit-code", "problems", "good.log", "flaky.log"));
        assertEquals(0, run("-q", "--exit-code", "problems", "good.log", "good-2.log", "flaky.log"));

        write("a/app.log", "INFO start", "INFO stop");
        write("b/app.log", "INFO start", "ERROR boom", "INFO stop");
        write("a/same.log", "INFO x");
        write("b/same.log", "INFO x");
        assertEquals(1, run("-f", "summary", "a", "b"));
        assertTrue(out().contains("app.log"), out());
        assertEquals(0, run("-q", "--include", "same.log", "a", "b"));
        assertEquals(1, run("check", "-q", "-b", "a", "b"));
        assertEquals(2, run("a", "good.log"));
        assertEquals(1, run("-f", "junit", "-o", "folder.xml", "a", "b"));
        assertTrue(Files.readString(dir.resolve("folder.xml")).contains("<testsuites"));
    }

    @Test
    @DisplayName("diff --session compares the logs saved in a session")
    void session() throws IOException {
        Files.writeString(dir.resolve("s.ldsession"), """
                [session]
                left = good.log
                baseline = good-2.log
                right = flaky.log

                [bookmark]
                side = right
                line = 2
                note = "slow again"
                """);
        assertEquals(0, run("-q", "--exit-code", "problems", "--session", "s.ldsession"));
        assertEquals(2, run("--session", "s.ldsession", "good.log"));
        assertFalse(err.toString(StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    @DisplayName("a missing extra baseline never causes an existing one to be overwritten")
    void partialBaselinesAreAnError() throws IOException {
        String before = Files.readString(dir.resolve("good.log"));
        assertEquals(2, run("check", "-q", "-u", "-b", "good.log", "-b", "missing.log", "bad.log"));
        assertEquals(before, Files.readString(dir.resolve("good.log")));
    }

    @Test
    @DisplayName("machine-readable output on stdout is not followed by the verdict")
    void verdictGoesToStderrForReports() {
        assertEquals(1, run("check", "-f", "json", "-b", "good.log", "bad.log"));
        assertTrue(out().strip().endsWith("}"), out());
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("FAIL"));
    }

    @Test
    @DisplayName("pruning a baseline folder only deletes runs that check stored")
    void pruningKeepsForeignFiles() throws IOException {
        write("runs/team-notes.txt", "INFO start session=a1", "INFO cache warm", "INFO stop");
        Files.setLastModifiedTime(dir.resolve("runs/team-notes.txt"), java.nio.file.attribute.FileTime.fromMillis(0));
        assertEquals(0, run("check", "-q", "--update-on-pass", "-B", "runs", "--keep", "1", "good.log"));
        assertEquals(0, run("check", "-q", "--update-on-pass", "-B", "runs", "--keep", "1", "good.log"));
        assertTrue(Files.exists(dir.resolve("runs/team-notes.txt")));
    }
}
