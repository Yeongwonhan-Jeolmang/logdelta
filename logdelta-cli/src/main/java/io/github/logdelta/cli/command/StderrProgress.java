// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.command;

import io.github.logdelta.core.util.ProgressListener;

import java.io.PrintStream;
import java.util.Locale;

/// A single-line progress indicator on standard error that overwrites itself.
final class StderrProgress implements ProgressListener {

    private final PrintStream err;
    private long lastPrint;

    StderrProgress(PrintStream err) {
        this.err = err;
    }

    /// Returns a progress indicator if `enabled`, otherwise [ProgressListener#NONE].
    static ProgressListener of(boolean enabled, PrintStream err) {
        return enabled ? new StderrProgress(err) : ProgressListener.NONE;
    }

    /// Clears the progress line if `progress` is a [StderrProgress].
    static void clear(ProgressListener progress) {
        if (progress instanceof StderrProgress p) {
            p.err.print("\r\u001b[K");
            p.err.flush();
        }
    }

    @Override
    public synchronized void onProgress(String stage, double fraction) {
        long now = System.nanoTime();
        if (now - lastPrint < 100_000_000L && fraction < 1) {
            return;
        }
        lastPrint = now;
        String pct = fraction < 0 ? "" : String.format(Locale.ROOT, " %3.0f%%", fraction * 100);
        err.print("\r\u001b[K" + stage + pct);
        err.flush();
    }
}
