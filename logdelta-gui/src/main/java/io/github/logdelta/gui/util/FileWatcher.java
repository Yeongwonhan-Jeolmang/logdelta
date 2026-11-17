// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.util;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.Set;

/// Watches a set of files and calls back on the event dispatch thread when any of them changes.
///
/// Logs are often written in bursts, so notifications are debounced: the callback runs once
/// the files have been quiet for [#QUIET_MS] milliseconds.
public final class FileWatcher implements AutoCloseable {

    /// Quiet period before the callback runs.
    public static final int QUIET_MS = 600;

    private final Runnable onChange;
    private final Timer debounce;
    private WatchService service;
    private Thread thread;
    private Set<Path> files = Set.of();

    /// @param onChange invoked on the event dispatch thread after a change
    public FileWatcher(Runnable onChange) {
        this.onChange = onChange;
        this.debounce = new Timer(QUIET_MS, _ -> this.onChange.run());
        this.debounce.setRepeats(false);
    }

    /// Starts watching `paths`, replacing any previous set.
    public synchronized void watch(Set<Path> paths) throws IOException {
        stop();
        files = new HashSet<>();
        for (Path p : paths) {
            files.add(p.toAbsolutePath().normalize());
        }
        service = FileSystems.getDefault().newWatchService();
        Set<Path> dirs = new HashSet<>();
        for (Path f : files) {
            Path dir = f.getParent();
            if (dir != null && dirs.add(dir)) {
                dir.register(service, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
            }
        }
        WatchService ws = service;
        Set<Path> watched = Set.copyOf(files);
        // The loop spends its life blocked in WatchService.take(): a perfect fit for a virtual thread.
        thread = Thread.ofVirtual().name("logdelta-file-watcher").start(() -> loop(ws, watched));
    }

    private void loop(WatchService ws, Set<Path> watched) {
        try {
            while (true) {
                WatchKey key = ws.take();
                Path dir = (Path) key.watchable();
                boolean relevant = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context() instanceof Path name && watched.contains(dir.resolve(name).normalize())) {
                        relevant = true;
                    }
                }
                key.reset();
                if (relevant) {
                    SwingUtilities.invokeLater(debounce::restart);
                }
            }
        } catch (InterruptedException | ClosedWatchServiceException _) {
            // stopped
        }
    }

    /// Stops watching.
    public synchronized void stop() {
        debounce.stop();
        if (service != null) {
            try {
                service.close();
            } catch (IOException _) {
                // closing a watch service does not fail in practice
            }
            service = null;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    @Override
    public void close() {
        stop();
    }
}
