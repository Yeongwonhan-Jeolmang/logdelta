// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.command;

import io.github.logdelta.cli.CliContext;
import io.github.logdelta.cli.LogDeltaCli;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Command-line features added in 2.0: structured logs, presets, time windows, redaction,
/// automatic profiles, completions and the man page.
class CliFeaturesTest {

    private Path dir;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("logdelta-cli2");
        Files.createDirectory(dir.resolve(".git")); // stop the profile search here
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
    }

    private int run(Path workingDir, String... args) {
        out.reset();
        err.reset();
        CliContext ctx = new CliContext(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), new ByteArrayInputStream(new byte[0]),
                Map.of(), false, workingDir, true);
        return new LogDeltaCli().run(args, ctx);
    }

    private int run(String... args) {
        return run(dir, args);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("JSON logs compare field by field; --ignore-field and --compare-field adjust it")
    void structured() throws IOException {
        Files.writeString(dir.resolve("a.jsonl"), "{\"ts\":1,\"msg\":\"hi\",\"user\":\"a\"}\n");
        Files.writeString(dir.resolve("b.jsonl"), "{\"user\":\"b\",\"msg\":\"hi\",\"ts\":2}\n");
        assertEquals(1, run("-q", "a.jsonl", "b.jsonl"));
        assertEquals(0, run("-q", "--ignore-field", "user", "a.jsonl", "b.jsonl"));
        assertEquals(1, run("-q", "--ignore-field", "user", "--compare-field", "ts", "a.jsonl", "b.jsonl"));
        assertEquals(1, run("-q", "--ignore-field", "user", "--compare-field", "TS", "a.jsonl", "b.jsonl"));
        assertEquals(1, run("-q", "--log-format", "plain", "--ignore-field", "user", "a.jsonl", "b.jsonl"));
        assertEquals(0, run("-f", "summary", "--ignore-field", "user", "a.jsonl", "b.jsonl"));
        assertTrue(out().contains("format: JSON lines (detected)"), out());
        assertEquals(2, run("-q", "--log-format", "yaml", "a.jsonl", "b.jsonl"));
        assertEquals(0, run("normalize", "a.jsonl"));
        assertTrue(out().contains("\"ts\":<IGNORED>"), "normalize shows what diff compares: " + out());
        assertEquals(0, run("explain", "--color", "never", "{\"ts\":1,\"msg\":\"hi\"}"));
        assertTrue(out().contains("ignored-field"), out());
    }

    @Test
    @DisplayName("--since and --until compare only part of each run")
    void timeWindow() throws IOException {
        Files.writeString(dir.resolve("a.log"), """
                2026-01-01 10:00:00 INFO start
                2026-01-01 10:05:00 INFO middle
                2026-01-01 10:10:00 INFO end
                """);
        Files.writeString(dir.resolve("b.log"), """
                2026-02-02 08:00:00 ERROR start failed
                2026-02-02 08:05:00 INFO middle
                2026-02-02 08:10:00 ERROR end failed
                """);
        assertEquals(1, run("-q", "a.log", "b.log"));
        assertEquals(0, run("-q", "--since", "+4m", "--until", "+6m", "a.log", "b.log"));
        assertEquals(2, run("-q", "--since", "later", "a.log", "b.log"));
        assertEquals(2, run("-q", "--since", "+5m", "--until", "+1m", "a.log", "b.log"));
        assertEquals(0, run("--since", "+1h", "a.log", "b.log"));
        assertTrue(err().contains("no line of a.log is inside the time window"), err());
        Files.writeString(dir.resolve("w.ldprofile"), "[options]\nuntil = +6m\n");
        assertEquals(0, run("-q", "-p", "w.ldprofile", "--since", "+4m", "a.log", "b.log"),
                "--since keeps the profile's until");
    }

    @Test
    @DisplayName("reports remove secrets unless --no-redact is given; terminal output keeps them")
    void redaction() throws IOException {
        Files.writeString(dir.resolve("a.log"), "login ok\n");
        Files.writeString(dir.resolve("b.log"), "login failed password=hunter2\n");
        assertEquals(1, run("-f", "markdown", "a.log", "b.log"));
        assertFalse(out().contains("hunter2"));
        assertTrue(err().contains("removed 1 secret"), err());
        assertEquals(1, run("-f", "markdown", "--no-redact", "a.log", "b.log"));
        assertTrue(out().contains("hunter2"));
        assertEquals(1, run("-f", "unified", "--redact", "login \\w+", "--no-redact", "a.log", "b.log"));
        assertFalse(out().contains("login failed"));
        assertEquals(1, run("--color", "never", "-f", "side-by-side", "a.log", "b.log"));
        assertTrue(out().contains("hunter2"), "the terminal view is not an export");
        assertEquals(1, run("-f", "summary", "-o", "summary.txt", "a.log", "b.log"));
        assertFalse(Files.readString(dir.resolve("summary.txt")).contains("hunter2"), "a file is a report");
        Files.writeString(dir.resolve("c.log"), "login failed password=hunter2\nERROR token=abc123\n");
        assertEquals(1, run("check", "-b", "a.log", "c.log"));
        assertFalse(out().contains("abc123"), "check output goes to CI logs");
    }

    @Test
    @DisplayName("a .logdelta profile in the folder or a parent is used unless --no-profile")
    void autoProfile() throws IOException {
        Files.writeString(dir.resolve("a.log"), "INFO a\nDEBUG noise 1\n");
        Files.writeString(dir.resolve("b.log"), "INFO a\nDEBUG noise 2\n");
        assertEquals(1, run("-q", "a.log", "b.log"));
        Files.writeString(dir.resolve(".logdelta"), "[ignore]\npattern = ^DEBUG\n");
        Path sub = Files.createDirectories(dir.resolve("sub/deeper"));
        assertEquals(0, run(sub, "-q", "../../a.log", "../../b.log"));
        assertEquals(0, run(sub, "../../a.log", "../../b.log"));
        assertTrue(err().contains("using profile"), err());
        assertEquals(1, run(sub, "-q", "--no-profile", "../../a.log", "../../b.log"));
        assertEquals(OptionSupport.AUTO_PROFILE, OptionSupport.findAutoProfile(sub).getFileName().toString());
        Files.delete(dir.resolve(".logdelta"));
        Files.createDirectory(dir.resolve(".logdelta"));
        assertNull(OptionSupport.findAutoProfile(sub), "a .logdelta folder without a profile is not a profile");
        Files.writeString(dir.resolve(".logdelta/profile.ldprofile"), "[ignore]\npattern = ^DEBUG\n");
        assertEquals(0, run(sub, "-q", "../../a.log", "../../b.log"));
        // The search ends at the home folder and skips folders anyone can write to, such as /tmp.
        assertNull(OptionSupport.findAutoProfile(sub, sub.getParent()));
        try {
            java.nio.file.attribute.PosixFileAttributeView view =
                    Files.getFileAttributeView(dir, java.nio.file.attribute.PosixFileAttributeView.class);
            if (view != null) {
                view.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
                assertNull(OptionSupport.findAutoProfile(sub, null), "a world-writable folder is not trusted");
                view.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            }
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system
        }
    }

    @Test
    @DisplayName("completion scripts and the man page cover every command")
    void completionAndManPage() {
        for (String shell : CompletionCommand.SHELLS) {
            assertEquals(0, run("completion", shell));
            String script = out();
            assertTrue(script.contains("check"), shell);
            assertTrue(script.contains("--log-format") || script.contains("log-format"), shell);
            assertTrue(script.contains("logdelta-cli"), shell);
        }
        assertEquals(2, run("completion", "tcsh"));
        assertEquals(0, run("manpage", "--date", "2026-11-23"));
        String man = out();
        assertTrue(man.startsWith(".\\\""));
        assertTrue(man.contains(".TH LOGDELTA 1 \"2026-11-23\""));
        for (String cmd : new String[] {"DIFF OPTIONS", "CHECK OPTIONS", "COMPLETION OPTIONS"}) {
            assertTrue(man.contains(".SH \"" + cmd + "\""), cmd);
        }
        assertTrue(man.contains("\\-\\-since"));
        assertEquals("\\&.x \\- y \\e", ManCommand.esc(".x - y \\"));
    }

    @Test
    @DisplayName("rules --presets and --redaction describe what is built in")
    void rulesListings() {
        assertEquals(0, run("rules", "--presets", "--color", "never"));
        assertTrue(out().contains("spring-boot.pid"));
        assertTrue(out().contains("--preset logcat"));
        assertEquals(0, run("rules", "--redaction", "--color", "never"));
        assertTrue(out().contains("github-token"));
        assertEquals(0, run("rules", "--preset", "journald", "--color", "never"));
        assertTrue(out().contains("journald.host"));
    }
}
