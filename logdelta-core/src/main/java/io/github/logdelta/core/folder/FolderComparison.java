// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.folder;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// The result of comparing two folders of logs.
///
/// @param leftDir       baseline folder
/// @param rightDir      candidate folder
/// @param entries       one entry per file or matched pair, ordered for review: files with new
///                      problems first, then the other changes by similarity, then the rest
/// @param elapsedMillis wall-clock time spent
public record FolderComparison(Path leftDir, Path rightDir, List<FolderEntry> entries, long elapsedMillis) {

    /// Copies the entry list.
    public FolderComparison {
        Objects.requireNonNull(leftDir, "leftDir");
        Objects.requireNonNull(rightDir, "rightDir");
        entries = List.copyOf(entries);
    }

    /// Number of entries with the given status.
    public int count(FolderEntry.Status status) {
        return (int) entries.stream().filter(e -> e.status() == status).count();
    }

    /// Total number of new problems across all files.
    public int newProblems() {
        return entries.stream().mapToInt(FolderEntry::newProblemCount).sum();
    }

    /// Whether every file exists on both sides and is identical.
    public boolean identical() {
        return entries.stream().allMatch(e -> e.status() == FolderEntry.Status.IDENTICAL
                || e.status() == FolderEntry.Status.SKIPPED);
    }
}
