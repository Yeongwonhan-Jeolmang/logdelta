// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.folder;

import io.github.logdelta.core.analysis.InsightItem;
import io.github.logdelta.core.model.DiffStats;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// The outcome for one file (or pair of files) in a [FolderComparison].
///
/// @param name        display name: the relative path, or `left → right` for a rotated pair
/// @param left        baseline file, or `null` if it only exists on the right
/// @param right       candidate file, or `null` if it only exists on the left
/// @param status      what happened
/// @param stats       comparison counts, or `null` unless the pair was compared
/// @param newProblems problems only present in the candidate (empty unless compared)
/// @param resolved    number of problems that disappeared
/// @param message     reason for [Status#FAILED] or [Status#SKIPPED], otherwise empty
public record FolderEntry(String name, Path left, Path right, Status status, DiffStats stats,
                          List<InsightItem> newProblems, int resolved, String message) {

    /// What happened to a file.
    public enum Status {
        /// Identical after normalization (apart from expected variation).
        IDENTICAL,
        /// Compared and different.
        CHANGED,
        /// Exists only in the baseline folder.
        ONLY_LEFT,
        /// Exists only in the candidate folder.
        ONLY_RIGHT,
        /// Not compared because it looks binary.
        SKIPPED,
        /// Could not be read or compared.
        FAILED;

        /// Short label for tables.
        public String label() {
            return switch (this) {
                case IDENTICAL -> "identical";
                case CHANGED -> "changed";
                case ONLY_LEFT -> "only in baseline";
                case ONLY_RIGHT -> "only in candidate";
                case SKIPPED -> "skipped";
                case FAILED -> "failed";
            };
        }
    }

    /// Validates the values and copies the list.
    public FolderEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        newProblems = List.copyOf(newProblems);
        message = message == null ? "" : message;
    }

    /// Whether both sides exist and were compared.
    public boolean compared() {
        return stats != null;
    }

    /// Similarity in `[0, 1]`, or `-1` when the pair was not compared.
    public double similarity() {
        return stats == null ? -1 : stats.similarity();
    }

    /// Number of new problems.
    public int newProblemCount() {
        return newProblems.size();
    }
}
