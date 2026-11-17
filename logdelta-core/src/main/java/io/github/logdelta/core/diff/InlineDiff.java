// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.diff;

import io.github.logdelta.core.normalize.Mask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Finds the words that changed between two versions of a line.
///
/// Both lines are split into tokens: runs of letters/digits/underscores, runs of whitespace,
/// and single punctuation characters. A masked region (for example a timestamp) becomes a single
/// token whose comparison value is the mask's replacement. Two different timestamps therefore
/// compare as equal and are not highlighted. Tokens are diffed with [MyersDiff] in minimal
/// mode. Changed tokens are then merged into spans, and the whitespace between two changed tokens
/// is absorbed so that highlights read as phrases instead of a stutter of single words.
public final class InlineDiff {

    private static final MyersDiff MYERS = new MyersDiff(true);

    private InlineDiff() {
    }

    /// Compares two lines without masks, exact case and whitespace.
    public static InlineHighlights compute(String left, String right) {
        return compute(left, List.of(), right, List.of(), false, false);
    }

    /// Compares two lines.
    ///
    /// @param left             left line text
    /// @param leftMasks        masks within `left` (may be empty)
    /// @param right            right line text
    /// @param rightMasks       masks within `right` (may be empty)
    /// @param ignoreCase       compare tokens case-insensitively
    /// @param ignoreWhitespace treat all whitespace runs as equal
    public static InlineHighlights compute(String left, List<Mask> leftMasks, String right, List<Mask> rightMasks,
                                           boolean ignoreCase, boolean ignoreWhitespace) {
        List<Token> lt = tokenize(left, leftMasks, ignoreCase, ignoreWhitespace);
        List<Token> rt = tokenize(right, rightMasks, ignoreCase, ignoreWhitespace);
        Map<String, Integer> ids = new HashMap<>();
        int[] a = intern(lt, ids);
        int[] b = intern(rt, ids);
        List<Span> ls = new ArrayList<>();
        List<Span> rs = new ArrayList<>();
        for (Edit e : MYERS.diff(a, b)) {
            if (e.type() == Edit.Type.DELETE) {
                addSpan(ls, lt, e.aStart(), e.aEnd(), left);
            } else if (e.type() == Edit.Type.INSERT) {
                addSpan(rs, rt, e.bStart(), e.bEnd(), right);
            }
        }
        return new InlineHighlights(ls, rs);
    }

    private static void addSpan(List<Span> spans, List<Token> tokens, int from, int to, String text) {
        // Trim whitespace-only tokens at both ends: highlighting a lone space is noise.
        while (from < to && tokens.get(from).whitespace) {
            from++;
        }
        while (to > from && tokens.get(to - 1).whitespace) {
            to--;
        }
        if (from >= to) {
            return;
        }
        int start = tokens.get(from).start;
        int end = tokens.get(to - 1).end;
        if (!spans.isEmpty()) {
            Span last = spans.getLast();
            if (text.substring(last.end(), start).isBlank()) {
                spans.removeLast();
                spans.add(new Span(last.start(), end));
                return;
            }
        }
        spans.add(new Span(start, end));
    }

    private static int[] intern(List<Token> tokens, Map<String, Integer> ids) {
        int[] out = new int[tokens.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ids.computeIfAbsent(tokens.get(i).key, _ -> ids.size());
        }
        return out;
    }

    /// Splits a line into tokens; package-private for tests.
    static List<Token> tokenize(String s, List<Mask> masks, boolean ignoreCase, boolean ignoreWhitespace) {
        List<Token> out = new ArrayList<>();
        int mi = 0;
        int i = 0;
        while (i < s.length()) {
            while (mi < masks.size() && masks.get(mi).end() <= i) {
                mi++;
            }
            if (mi < masks.size() && masks.get(mi).start() <= i) {
                Mask m = masks.get(mi);
                out.add(new Token(m.start(), m.end(), "\u0000" + m.replacement(), false));
                i = m.end();
                mi++;
                continue;
            }
            int limit = mi < masks.size() ? masks.get(mi).start() : s.length();
            char c = s.charAt(i);
            int j = i + 1;
            boolean ws = false;
            if (isWordChar(c)) {
                while (j < limit && isWordChar(s.charAt(j))) {
                    j++;
                }
            } else if (Character.isWhitespace(c)) {
                ws = true;
                while (j < limit && Character.isWhitespace(s.charAt(j))) {
                    j++;
                }
            } else if (Character.isHighSurrogate(c) && j < limit) {
                j++;
            }
            String key = s.substring(i, j);
            if (ws && ignoreWhitespace) {
                key = " ";
            } else if (ignoreCase) {
                key = key.toLowerCase(Locale.ROOT);
            }
            out.add(new Token(i, j, key, ws));
            i = j;
        }
        return out;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /// A token with its offsets in the original line and its comparison key.
    record Token(int start, int end, String key, boolean whitespace) {
    }
}
