// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.folder;

import java.util.List;

/// Settings for [FolderComparer].
///
/// Glob patterns use [java.nio.file.FileSystem#getPathMatcher(String)] `glob:` syntax and
/// are matched against the path relative to the compared folder, with `/` as separator. A
/// pattern without a `/` is also tried against the bare file name, so `*.log` matches logs
/// in subfolders too.
///
/// @param include      files to compare; empty means every regular file
/// @param exclude      files to leave out, applied after `include`
/// @param recursive    whether to descend into subfolders
/// @param matchRotated pair files whose names differ only in digits (dates, counters,
///                     build numbers), such as `app-2026-11-02.log` and `app-2026-11-03.log`,
///                     when no file with the exact name exists on the other side
/// @param parallelism  number of files compared at the same time; `0` picks a value from the
///                     number of processors
public record FolderOptions(List<String> include, List<String> exclude, boolean recursive, boolean matchRotated,
                            int parallelism) {

    /// All files, recursive, rotated-name matching on, automatic parallelism.
    public static final FolderOptions DEFAULT = new FolderOptions(List.of(), List.of(), true, true, 0);

    /// Copies the lists and validates `parallelism`.
    public FolderOptions {
        include = List.copyOf(include);
        exclude = List.copyOf(exclude);
        if (parallelism < 0) {
            throw new IllegalArgumentException("parallelism must be >= 0");
        }
    }

    /// Returns a copy with different include patterns.
    public FolderOptions withInclude(List<String> patterns) {
        return new FolderOptions(patterns, exclude, recursive, matchRotated, parallelism);
    }

    /// Returns a copy with different exclude patterns.
    public FolderOptions withExclude(List<String> patterns) {
        return new FolderOptions(include, patterns, recursive, matchRotated, parallelism);
    }

    /// Returns a copy with recursion switched on or off.
    public FolderOptions withRecursive(boolean v) {
        return new FolderOptions(include, exclude, v, matchRotated, parallelism);
    }

    /// Returns a copy with rotated-name matching switched on or off.
    public FolderOptions withMatchRotated(boolean v) {
        return new FolderOptions(include, exclude, recursive, v, parallelism);
    }

    /// The parallelism to use: [#parallelism()] or a value derived from the processor count.
    public int effectiveParallelism() {
        return parallelism > 0 ? parallelism : Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 8);
    }
}
