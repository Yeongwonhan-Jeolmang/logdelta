// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.folder;

import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.report.FolderReports;
import io.github.logdelta.core.report.ReportFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FolderComparerTest {

    private static void write(Path dir, String name, String... lines) throws Exception {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.write(p, List.of(lines));
    }

    @Test
    @DisplayName("pairs files by path and rotation key and orders them for review")
    void compareFolders() throws Exception {
        Path root = Files.createTempDirectory("logdelta-folder");
        Path a = root.resolve("a");
        Path b = root.resolve("b");
        write(a, "api.log", "INFO start", "INFO ready", "INFO stop");
        write(b, "api.log", "INFO start", "ERROR db down", "INFO stop");
        write(a, "same.log", "INFO x");
        write(b, "same.log", "INFO x");
        write(a, "worker/app-2026-11-02.log", "INFO job 1", "INFO done");
        write(b, "worker/app-2026-11-03.log", "INFO job 1", "INFO done", "INFO extra");
        write(a, "old.log", "INFO bye");
        write(b, "crash.log", "FATAL out of memory");
        write(b, ".hidden.log", "ERROR ignored");
        Files.write(b.resolve("core.bin"), new byte[] {1, 0, 2, 0});
        Files.write(a.resolve("core.bin"), new byte[] {1, 0, 2, 0});

        FolderComparison c = new FolderComparer().compare(a, b, DiffOptions.defaults(), FolderOptions.DEFAULT);
        Map<String, FolderEntry> byName = new TreeMap<>();
        c.entries().forEach(e -> byName.put(e.name(), e));

        assertEquals(List.of("api.log", "core.bin", "crash.log", "old.log", "same.log",
                "worker/app-2026-11-02.log → worker/app-2026-11-03.log"), List.copyOf(byName.keySet()));
        assertEquals(FolderEntry.Status.CHANGED, byName.get("api.log").status());
        assertEquals(1, byName.get("api.log").newProblemCount());
        assertEquals(FolderEntry.Status.IDENTICAL, byName.get("same.log").status());
        assertEquals(FolderEntry.Status.ONLY_RIGHT, byName.get("crash.log").status());
        assertEquals(1, byName.get("crash.log").newProblemCount());
        assertEquals(FolderEntry.Status.ONLY_LEFT, byName.get("old.log").status());
        assertEquals(FolderEntry.Status.SKIPPED, byName.get("core.bin").status());
        assertEquals(FolderEntry.Status.CHANGED,
                byName.get("worker/app-2026-11-02.log → worker/app-2026-11-03.log").status());

        // review order: new problems first, identical and skipped last
        assertEquals(1, c.entries().getFirst().newProblemCount());
        assertEquals(FolderEntry.Status.SKIPPED, c.entries().getLast().status());
        assertEquals(2, c.newProblems());
        assertFalse(c.identical());

        String md = FolderReports.render(c, ReportFormat.MARKDOWN);
        assertTrue(md.contains("| `api.log` | changed |"), md);
        String junit = FolderReports.render(c, ReportFormat.JUNIT);
        assertTrue(junit.contains("failures=\"2\""), junit);
        String json = FolderReports.render(c, ReportFormat.JSON);
        assertTrue(json.contains("\"kind\": \"folder\""), json);
    }

    @Test
    @DisplayName("include and exclude globs match names in subfolders")
    void filters() throws Exception {
        Path root = Files.createTempDirectory("logdelta-folder");
        write(root, "a.log", "x");
        write(root, "a.txt", "x");
        write(root, "sub/b.log", "x");
        write(root, "sub/skip.log", "x");
        FolderOptions o = FolderOptions.DEFAULT.withInclude(List.of("*.log")).withExclude(List.of("sub/skip.log"));
        assertEquals(List.of("a.log", "sub/b.log"), List.copyOf(FolderComparer.list(root, o).keySet()));
        assertEquals(List.of("a.log"), List.copyOf(FolderComparer.list(root, o.withRecursive(false)).keySet()));
    }

    @Test
    @DisplayName("rotation keys only pair unambiguous names")
    void rotation() {
        assertEquals("app-#-#-#.log", FolderComparer.rotationKey("app-2026-11-02.log"));
        var pairs = FolderComparer.pair(
                new TreeMap<>(Map.of("w1.log", Path.of("a/w1.log"), "w2.log", Path.of("a/w2.log"))),
                new TreeMap<>(Map.of("w3.log", Path.of("b/w3.log"))), true);
        assertEquals(3, pairs.size());
        assertTrue(pairs.stream().noneMatch(p -> p.left() != null && p.right() != null));
    }
}
