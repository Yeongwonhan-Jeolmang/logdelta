// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.folder;

import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.engine.LogDeltaEngine;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.io.LogReader;
import io.github.logdelta.core.util.CancellationToken;
import io.github.logdelta.core.util.CancelledException;
import io.github.logdelta.core.util.ProgressListener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/// Compares every log in one folder with its counterpart in another.
///
/// Files are paired by relative path. With [FolderOptions#matchRotated()], files left over on
/// both sides are then paired by their *rotation key*: the relative path with every run of
/// digits replaced by `#`, so `app-2026-11-02.log` pairs with `app-2026-11-03.log` and
/// `worker.3.log` with `worker.4.log`. A key only pairs when it is unique on both sides.
///
/// Each pair is compared with the regular [LogDeltaEngine]. Files that only exist in the
/// candidate folder are still scanned for problems (as if compared with an empty log), so a
/// brand-new `crash.log` shows up with its errors. Only the summary of each comparison is
/// kept, which keeps memory flat for large folders; open a pair with the engine to see the
/// full diff.
public final class FolderComparer {

    private static final int BINARY_SNIFF_BYTES = 8192;

    private final LogDeltaEngine engine;

    /// Creates a comparer that uses a new engine.
    public FolderComparer() {
        this(new LogDeltaEngine());
    }

    /// Creates a comparer that uses the given engine.
    public FolderComparer(LogDeltaEngine engine) {
        this.engine = engine;
    }

    /// Compares two folders.
    public FolderComparison compare(Path leftDir, Path rightDir, DiffOptions options, FolderOptions folderOptions)
            throws IOException {
        return compare(leftDir, List.of(), rightDir, options, folderOptions, ProgressListener.NONE,
                CancellationToken.NEVER);
    }

    /// Compares two folders, using further baseline folders to recognise normal variation.
    ///
    /// For each pair, files with the same relative path in `extraBaselineDirs` are passed to
    /// the engine as extra baselines; folders that lack the file are ignored for that pair.
    ///
    /// @throws NotDirectoryException if `leftDir` or `rightDir` is not a folder
    /// @throws CancelledException    if `token` is cancelled
    public FolderComparison compare(Path leftDir, List<Path> extraBaselineDirs, Path rightDir, DiffOptions options,
                                    FolderOptions folderOptions, ProgressListener progress,
                                    CancellationToken token) throws IOException {
        long start = System.nanoTime();
        Map<String, Path> left = list(leftDir, folderOptions);
        Map<String, Path> right = list(rightDir, folderOptions);
        List<Pair> pairs = pair(left, right, folderOptions.matchRotated());
        progress.onProgress("Comparing", 0);

        int threads = Math.min(folderOptions.effectiveParallelism(), Math.max(1, pairs.size()));
        AtomicInteger done = new AtomicInteger();
        List<FolderEntry> entries = new ArrayList<>(pairs.size());
        try (ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "logdelta-folder");
            t.setDaemon(true);
            return t;
        })) {
            List<Future<FolderEntry>> futures = new ArrayList<>(pairs.size());
            for (Pair p : pairs) {
                futures.add(pool.submit(() -> {
                    try {
                        return compareOne(p, extraBaselineDirs, options, token);
                    } finally {
                        progress.onProgress("Comparing", done.incrementAndGet() / (double) pairs.size());
                    }
                }));
            }
            for (Future<FolderEntry> f : futures) {
                try {
                    entries.add(f.get());
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof CancelledException c) {
                        pool.shutdownNow();
                        throw c;
                    }
                    throw new IllegalStateException(e.getCause());
                } catch (InterruptedException e) {
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                    throw new CancelledException();
                }
            }
        }
        entries.sort(REVIEW_ORDER);
        progress.onProgress("Comparing", 1);
        return new FolderComparison(leftDir, rightDir, entries, (System.nanoTime() - start) / 1_000_000);
    }

    /// Files with new problems first, then other changes by similarity, then everything else.
    static final Comparator<FolderEntry> REVIEW_ORDER = Comparator
            .comparingInt((FolderEntry e) -> -e.newProblemCount())
            .thenComparingInt(e -> rank(e.status()))
            .thenComparingDouble(FolderEntry::similarity)
            .thenComparing(FolderEntry::name);

    private static int rank(FolderEntry.Status s) {
        return switch (s) {
            case CHANGED -> 0;
            case ONLY_RIGHT -> 1;
            case ONLY_LEFT -> 2;
            case FAILED -> 3;
            case IDENTICAL -> 4;
            case SKIPPED -> 5;
        };
    }

    private FolderEntry compareOne(Pair p, List<Path> extraDirs, DiffOptions options, CancellationToken token) {
        token.throwIfCancelled();
        try {
            if (p.left != null && isBinary(p.left) || p.right != null && isBinary(p.right)) {
                return new FolderEntry(p.name, p.left, p.right, FolderEntry.Status.SKIPPED, null, List.of(), 0,
                        "binary file");
            }
            if (p.left == null) {
                LogFile r = LogReader.read(p.right);
                DiffResult res = engine.compare(LogFile.ofLines("(none)", List.of()), List.of(), r, options,
                        ProgressListener.NONE, token);
                return new FolderEntry(p.name, null, p.right, FolderEntry.Status.ONLY_RIGHT, null,
                        res.insights().newProblems(), 0, "");
            }
            if (p.right == null) {
                return new FolderEntry(p.name, p.left, null, FolderEntry.Status.ONLY_LEFT, null, List.of(), 0, "");
            }
            LogFile l = LogReader.read(p.left);
            LogFile r = LogReader.read(p.right);
            List<LogFile> extras = new ArrayList<>();
            for (Path dir : extraDirs) {
                Path candidate = dir.resolve(p.leftRelative);
                if (Files.isRegularFile(candidate)) {
                    extras.add(LogReader.read(candidate));
                }
            }
            DiffResult res = engine.compare(l, extras, r, options, ProgressListener.NONE, token);
            FolderEntry.Status status = res.isIdentical() ? FolderEntry.Status.IDENTICAL : FolderEntry.Status.CHANGED;
            return new FolderEntry(p.name, p.left, p.right, status, res.stats(), res.insights().newProblems(),
                    res.insights().resolvedProblems().size(), "");
        } catch (IOException | RuntimeException e) {
            if (e instanceof CancelledException c) {
                throw c;
            }
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new FolderEntry(p.name, p.left, p.right, FolderEntry.Status.FAILED, null, List.of(), 0, msg);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Listing and pairing
    // ------------------------------------------------------------------------------------------

    /// Lists the regular, non-hidden files below `dir` that pass the filters of `options`, in
    /// sorted order. These are the files [#compare] would consider.
    public static List<Path> listFiles(Path dir, FolderOptions options) throws IOException {
        return List.copyOf(list(dir, options).values());
    }

    /// Lists the regular, non-hidden files below `dir` that pass the filters, keyed by relative
    /// path with `/` separators, in sorted order.
    static Map<String, Path> list(Path dir, FolderOptions o) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new NotDirectoryException(dir.toString());
        }
        List<PathMatcher> include = matchers(o.include());
        List<PathMatcher> exclude = matchers(o.exclude());
        Map<String, Path> out = new TreeMap<>();
        int depth = o.recursive() ? Integer.MAX_VALUE : 1;
        try (Stream<Path> walk = Files.walk(dir, depth)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                Path rel = dir.relativize(p);
                if (isHidden(rel)) {
                    continue;
                }
                String key = rel.toString().replace('\\', '/');
                Path relSlash = Path.of(key);
                if (!include.isEmpty() && !matchesAny(include, relSlash)) {
                    continue;
                }
                if (matchesAny(exclude, relSlash)) {
                    continue;
                }
                out.put(key, p);
            }
        }
        return out;
    }

    private static boolean isHidden(Path rel) {
        for (Path part : rel) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static List<PathMatcher> matchers(List<String> globs) {
        List<PathMatcher> out = new ArrayList<>();
        for (String g : globs) {
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + g);
            boolean nameOnly = !g.contains("/");
            out.add(nameOnly ? p -> m.matches(p) || p.getFileName() != null && m.matches(p.getFileName()) : m);
        }
        return out;
    }

    private static boolean matchesAny(List<PathMatcher> matchers, Path p) {
        for (PathMatcher m : matchers) {
            if (m.matches(p)) {
                return true;
            }
        }
        return false;
    }

    /// One unit of work: a matched pair, or a file present on one side only.
    record Pair(String name, String leftRelative, Path left, Path right) {
    }

    static List<Pair> pair(Map<String, Path> left, Map<String, Path> right, boolean matchRotated) {
        List<Pair> out = new ArrayList<>();
        Map<String, Path> onlyLeft = new LinkedHashMap<>();
        Map<String, Path> onlyRight = new LinkedHashMap<>(right);
        for (Map.Entry<String, Path> e : left.entrySet()) {
            Path r = onlyRight.remove(e.getKey());
            if (r != null) {
                out.add(new Pair(e.getKey(), e.getKey(), e.getValue(), r));
            } else {
                onlyLeft.put(e.getKey(), e.getValue());
            }
        }
        if (matchRotated && !onlyLeft.isEmpty() && !onlyRight.isEmpty()) {
            Map<String, List<String>> leftKeys = byRotationKey(onlyLeft.keySet());
            Map<String, List<String>> rightKeys = byRotationKey(onlyRight.keySet());
            for (Map.Entry<String, List<String>> e : leftKeys.entrySet()) {
                List<String> rs = rightKeys.get(e.getKey());
                if (e.getValue().size() == 1 && rs != null && rs.size() == 1) {
                    String l = e.getValue().getFirst();
                    String r = rs.getFirst();
                    out.add(new Pair(l + " → " + r, l, onlyLeft.remove(l), onlyRight.remove(r)));
                }
            }
        }
        onlyLeft.forEach((k, p) -> out.add(new Pair(k, k, p, null)));
        onlyRight.forEach((k, p) -> out.add(new Pair(k, k, null, p)));
        return out;
    }

    private static Map<String, List<String>> byRotationKey(Iterable<String> names) {
        Map<String, List<String>> out = new HashMap<>();
        for (String n : names) {
            out.computeIfAbsent(rotationKey(n), _ -> new ArrayList<>()).add(n);
        }
        return out;
    }

    /// The name with every run of digits replaced by `#`.
    static String rotationKey(String relativePath) {
        return relativePath.replaceAll("\\d+", "#");
    }

    /// Whether a file looks binary: it contains a NUL byte in its first 8 KiB and is neither
    /// gzip-compressed nor UTF-16 text with a byte order mark.
    static boolean isBinary(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(BINARY_SNIFF_BYTES);
        }
        if (head.length >= 2) {
            int b0 = head[0] & 0xFF;
            int b1 = head[1] & 0xFF;
            if (b0 == 0x1f && b1 == 0x8b || b0 == 0xFF && b1 == 0xFE || b0 == 0xFE && b1 == 0xFF) {
                return false;
            }
        }
        for (byte b : head) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }
}
