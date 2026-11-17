// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.report;

import io.github.logdelta.core.diff.Span;
import io.github.logdelta.core.normalize.Mask;

import java.util.List;
import java.util.TreeSet;

/// Splits a line into runs that share the same decoration (masked and/or highlighted), so that
/// renderers can wrap each run in the right markup without overlapping tags. Used by the HTML
/// writer, the terminal renderer and the GUI.
public final class LineMarkup {

    /// A run of characters with uniform decoration.
    ///
    /// @param start       inclusive start offset
    /// @param end         exclusive end offset
    /// @param mask        the mask covering the run, or `null`
    /// @param highlighted whether the run is part of an inline (word-level) change
    public record Run(int start, int end, Mask mask, boolean highlighted) {
    }

    /// Callback for [#runs].
    @FunctionalInterface
    public interface RunConsumer {
        /// Receives the next run.
        void accept(Run run);
    }

    private LineMarkup() {
    }

    /// Emits the runs of `text` in order. Masks and highlights must each be sorted and
    /// non-overlapping; ranges beyond the end of the text are clipped.
    public static void runs(String text, List<Mask> masks, List<Span> highlights, RunConsumer consumer) {
        if (masks.isEmpty() && highlights.isEmpty()) {
            if (!text.isEmpty()) {
                consumer.accept(new Run(0, text.length(), null, false));
            }
            return;
        }
        TreeSet<Integer> cuts = new TreeSet<>();
        cuts.add(0);
        cuts.add(text.length());
        for (Mask m : masks) {
            cuts.add(Math.min(m.start(), text.length()));
            cuts.add(Math.min(m.end(), text.length()));
        }
        for (Span s : highlights) {
            cuts.add(Math.min(s.start(), text.length()));
            cuts.add(Math.min(s.end(), text.length()));
        }
        Integer prev = null;
        int mi = 0;
        int hi = 0;
        for (int cut : cuts) {
            if (prev != null && cut > prev) {
                int start = prev;
                while (mi < masks.size() && masks.get(mi).end() <= start) {
                    mi++;
                }
                while (hi < highlights.size() && highlights.get(hi).end() <= start) {
                    hi++;
                }
                Mask mask = mi < masks.size() && masks.get(mi).start() <= start ? masks.get(mi) : null;
                boolean hl = hi < highlights.size() && highlights.get(hi).start() <= start;
                consumer.accept(new Run(start, cut, mask, hl));
            }
            prev = cut;
        }
    }
}
