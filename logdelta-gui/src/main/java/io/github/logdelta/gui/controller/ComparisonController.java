// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.controller;

import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.engine.LogDeltaEngine;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.io.LogReader;
import io.github.logdelta.core.io.LogSources;
import io.github.logdelta.core.util.CancellationToken;
import io.github.logdelta.core.util.CancelledException;
import io.github.logdelta.core.util.ProgressListener;
import io.github.logdelta.gui.model.Source;

import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

/// Owns the state of one comparison (sources, extra baselines, options, result) and runs
/// comparisons on a background thread.
///
/// Sources may be local files, URLs or pasted text (see [Source]); they are read fresh on every
/// run, so re-comparing picks up changes to growing log files.
///
/// All public methods must be called on the event dispatch thread, and listeners are notified
/// there. Starting a new comparison cancels the running one; results of superseded runs are
/// discarded.
public final class ComparisonController {

    /// Receives state changes. All methods have empty defaults.
    public interface Listener {
        /// The selected sources changed (either may be `null`).
        default void filesChanged(Source left, Source right) {
        }

        /// The list of extra baselines changed.
        default void baselinesChanged(List<Source> extraBaselines) {
        }

        /// The diff options changed (a re-comparison follows if both files are set).
        default void optionsChanged(DiffOptions options) {
        }

        /// A comparison started.
        default void comparisonStarted() {
        }

        /// Progress of the running comparison.
        default void progress(String stage, double fraction) {
        }

        /// A comparison finished.
        ///
        /// @param result  the new result
        /// @param refresh `true` if this re-ran the same files (keep the scroll position)
        default void comparisonFinished(DiffResult result, boolean refresh) {
        }

        /// A comparison failed or was cancelled (`message == null` for cancellation).
        default void comparisonFailed(String message) {
        }
    }

    private static final int OPTIONS_DEBOUNCE_MS = 250;

    private final LogDeltaEngine engine = new LogDeltaEngine();
    private final List<Listener> listeners = new ArrayList<>();
    private final Timer debounce;

    private Source left;
    private Source right;
    private List<Source> extraBaselines = List.of();
    private LogSources.RemoteOptions remote = LogSources.RemoteOptions.DEFAULT;
    private DiffOptions options;
    private DiffResult result;
    private Worker running;
    private long generation;

    /// @param initialOptions options to start with (usually restored from the last session)
    public ComparisonController(DiffOptions initialOptions) {
        this.options = Objects.requireNonNull(initialOptions);
        this.debounce = new Timer(OPTIONS_DEBOUNCE_MS, _ -> compare(true));
        this.debounce.setRepeats(false);
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    /// The baseline source, or `null`.
    public Source left() {
        return left;
    }

    /// The candidate source, or `null`.
    public Source right() {
        return right;
    }

    /// Further known-good runs; differences they explain are shown as normal variation.
    public List<Source> extraBaselines() {
        return extraBaselines;
    }

    /// Replaces the extra baselines and re-compares.
    public void setExtraBaselines(List<Source> sources) {
        extraBaselines = List.copyOf(sources);
        listeners.forEach(l -> l.baselinesChanged(extraBaselines));
        if (hasFiles()) {
            compare(true);
        }
    }

    /// Headers and limits used for URL sources.
    public void setRemoteOptions(LogSources.RemoteOptions options) {
        this.remote = Objects.requireNonNull(options);
    }

    public DiffOptions options() {
        return options;
    }

    /// The latest result, or `null`.
    public DiffResult result() {
        return result;
    }

    /// Whether a comparison is running.
    public boolean isBusy() {
        return running != null;
    }

    /// Whether both files are selected.
    public boolean hasFiles() {
        return left != null && right != null;
    }

    /// Sets both sources and compares them.
    public void open(Source leftFile, Source rightFile) {
        this.left = leftFile;
        this.right = rightFile;
        listeners.forEach(l -> l.filesChanged(left, right));
        compare(false);
    }

    /// Sets the baseline; compares if the candidate is already set.
    public void setLeft(Source file) {
        this.left = file;
        listeners.forEach(l -> l.filesChanged(left, right));
        if (hasFiles()) {
            compare(false);
        }
    }

    /// Sets the candidate; compares if the baseline is already set.
    public void setRight(Source file) {
        this.right = file;
        listeners.forEach(l -> l.filesChanged(left, right));
        if (hasFiles()) {
            compare(false);
        }
    }

    /// Swaps baseline and candidate.
    public void swap() {
        Source t = left;
        left = right;
        right = t;
        listeners.forEach(l -> l.filesChanged(left, right));
        if (hasFiles()) {
            compare(false);
        }
    }

    /// Replaces the options. If files are selected, a re-comparison is scheduled after a short
    /// delay so that rapid changes (toggling several rules) trigger only one run.
    public void setOptions(DiffOptions newOptions) {
        if (newOptions.equals(options)) {
            return;
        }
        this.options = newOptions;
        listeners.forEach(l -> l.optionsChanged(options));
        if (hasFiles()) {
            debounce.restart();
        }
    }

    /// Re-runs the comparison of the current files, keeping the view position.
    public void reload() {
        if (hasFiles()) {
            compare(true);
        }
    }

    /// Cancels the running comparison, if any.
    public void cancel() {
        if (running != null) {
            running.token.cancel();
        }
    }

    private void compare(boolean refresh) {
        debounce.stop();
        if (!hasFiles()) {
            return;
        }
        cancel();
        long gen = ++generation;
        Worker w = new Worker(left, extraBaselines, right, options, remote, refresh, gen);
        running = w;
        listeners.forEach(Listener::comparisonStarted);
        w.execute();
    }

    private final class Worker extends SwingWorker<DiffResult, Void> {
        final CancellationToken token = new CancellationToken();
        final Source l;
        final List<Source> extras;
        final Source r;
        final DiffOptions o;
        final LogSources.RemoteOptions remoteOptions;
        final boolean refresh;
        final long gen;

        Worker(Source l, List<Source> extras, Source r, DiffOptions o, LogSources.RemoteOptions remoteOptions,
               boolean refresh, long gen) {
            this.l = l;
            this.extras = extras;
            this.r = r;
            this.o = o;
            this.remoteOptions = remoteOptions;
            this.refresh = refresh;
            this.gen = gen;
        }

        @Override
        protected DiffResult doInBackground() throws Exception {
            ProgressListener progress = (stage, f) -> SwingUtilities.invokeLater(() -> {
                if (running == this) {
                    listeners.forEach(x -> x.progress(stage, f));
                }
            });
            int total = extras.size() + 2;
            LogFile leftLog = read(l, progress, 0, total);
            List<LogFile> extraLogs = new ArrayList<>();
            for (int i = 0; i < extras.size(); i++) {
                extraLogs.add(read(extras.get(i), progress, i + 1, total));
            }
            LogFile rightLog = read(r, progress, total - 1, total);
            return engine.compare(leftLog, extraLogs, rightLog, o,
                    (stage, f) -> progress.onProgress(stage, 0.3 + 0.7 * f), token);
        }

        private LogFile read(Source s, ProgressListener progress, int index, int total) throws java.io.IOException {
            double from = 0.3 * index / total;
            String stage = "Reading " + s.displayName();
            progress.onProgress(stage, from);
            return switch (s) {
                case Source.File(Path p) -> {
                    if (!Files.isRegularFile(p)) {
                        throw new NoSuchFileException(p.toString());
                    }
                    yield LogReader.read(p, null, (_, f) -> progress.onProgress(stage, from + f * 0.3 / total), token);
                }
                case Source.Url(java.net.URI uri) -> LogSources.fetch(uri, null, remoteOptions, token);
                case Source.Text(LogFile log) -> log;
            };
        }

        @Override
        protected void done() {
            if (gen != generation) {
                return; // superseded by a newer comparison
            }
            running = null;
            try {
                result = get();
                listeners.forEach(x -> x.comparisonFinished(result, refresh));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                listeners.forEach(x -> x.comparisonFailed(null));
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof CancelledException) {
                    listeners.forEach(x -> x.comparisonFailed(null));
                } else {
                    String msg = describe(cause);
                    listeners.forEach(x -> x.comparisonFailed(msg));
                }
            }
        }
    }

    private static String describe(Throwable t) {
        return switch (t) {
            case NoSuchFileException e -> "File not found: " + e.getMessage();
            case java.net.http.HttpTimeoutException _ -> "The server did not answer in time.";
            case java.net.ConnectException _ -> "Cannot connect: " + t.getMessage();
            case java.io.IOException e when e.getMessage() != null && e.getMessage().startsWith("HTTP ") ->
                    e.getMessage();
            case java.nio.file.AccessDeniedException e -> "Permission denied: " + e.getMessage();
            case OutOfMemoryError _ -> "Not enough memory for these files. Start LogDelta with a larger heap, e.g. -Xmx4g.";
            default -> t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        };
    }
}
