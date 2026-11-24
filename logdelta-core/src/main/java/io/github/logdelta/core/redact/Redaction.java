// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.redact;

import io.github.logdelta.core.diff.Span;
import io.github.logdelta.core.normalize.Mask;

import java.util.ArrayList;
import java.util.List;

/// The result of redacting one text: the new text and a way to translate offsets from the
/// original text into it, so that masks and highlights still line up.
public final class Redaction {

    private final String original;
    private final String text;
    /// Redacted ranges `{start, end}` in the original, ascending and disjoint.
    private final List<int[]> ranges;
    private final int replacementLength;

    private Redaction(String original, String text, List<int[]> ranges, int replacementLength) {
        this.original = original;
        this.text = text;
        this.ranges = ranges;
        this.replacementLength = replacementLength;
    }

    static Redaction unchanged(String text) {
        return new Redaction(text, text, List.of(), 0);
    }

    static Redaction of(String original, List<int[]> ranges, String replacement) {
        StringBuilder sb = new StringBuilder(original.length());
        int pos = 0;
        for (int[] r : ranges) {
            sb.append(original, pos, r[0]).append(replacement);
            pos = r[1];
        }
        sb.append(original, pos, original.length());
        return new Redaction(original, sb.toString(), List.copyOf(ranges), replacement.length());
    }

    /// The redacted text.
    public String text() {
        return text;
    }

    /// The text before redaction.
    public String original() {
        return original;
    }

    /// Whether anything was redacted.
    public boolean changed() {
        return !ranges.isEmpty();
    }

    /// Number of redacted secrets.
    public int count() {
        return ranges.size();
    }

    /// Translates an offset in the original text. Offsets inside a redacted range map to the start
    /// of its replacement when `start` is true, otherwise to its end.
    public int map(int offset, boolean start) {
        int shift = 0;
        for (int[] r : ranges) {
            if (offset <= r[0]) {
                break;
            }
            if (offset < r[1]) {
                return r[0] + shift + (start ? 0 : replacementLength);
            }
            shift += replacementLength - (r[1] - r[0]);
        }
        return offset + shift;
    }

    /// Translates highlight spans; spans that become empty are dropped.
    public List<Span> mapSpans(List<Span> spans) {
        if (ranges.isEmpty() || spans.isEmpty()) {
            return spans;
        }
        List<Span> out = new ArrayList<>(spans.size());
        for (Span s : spans) {
            int a = map(s.start(), true);
            int b = map(s.end(), false);
            if (!out.isEmpty() && out.getLast().end() > a) {
                a = out.getLast().end();
            }
            if (b > a) {
                out.add(new Span(a, b));
            }
        }
        return out;
    }

    /// Translates masks; a mask that overlaps a redacted range is dropped, because the secret's
    /// replacement now stands in that place.
    public List<Mask> mapMasks(List<Mask> masks) {
        if (ranges.isEmpty() || masks.isEmpty()) {
            return masks;
        }
        List<Mask> out = new ArrayList<>(masks.size());
        for (Mask m : masks) {
            if (overlapsRange(m.start(), m.end())) {
                continue;
            }
            out.add(new Mask(map(m.start(), true), map(m.end(), false), m.replacement(), m.ruleId()));
        }
        return out;
    }

    private boolean overlapsRange(int from, int to) {
        for (int[] r : ranges) {
            if (r[0] < to && from < r[1]) {
                return true;
            }
        }
        return false;
    }
}
