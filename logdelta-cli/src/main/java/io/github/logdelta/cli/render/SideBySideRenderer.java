// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.render;

import io.github.logdelta.core.diff.Span;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.Hunk;
import io.github.logdelta.core.model.ProcessedLine;
import io.github.logdelta.core.report.LineMarkup;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;

/// Renders a two-column comparison for a terminal.
///
/// ```
/// 12 - removed line                    |
/// 13 ~ text with old value             |    14 ~ text with new value
///    ... 120 unchanged lines
/// ```
///
/// Removed text is red, added text green, moved lines magenta. Masked regions are dimmed and
/// inline (word-level) changes are shown in reverse video. Lines longer than a column are
/// truncated with an ellipsis.
public final class SideBySideRenderer {

    private static final int MIN_WIDTH = 60;

    private final Ansi ansi;
    private final Glyphs glyphs;
    private final int width;
    private final String separator;

    /// @param ansi   styling
    /// @param glyphs box-drawing characters
    /// @param width  total terminal width in columns
    public SideBySideRenderer(Ansi ansi, Glyphs glyphs, int width) {
        this.ansi = ansi;
        this.glyphs = glyphs;
        this.width = Math.max(MIN_WIDTH, width);
        this.separator = " " + glyphs.vertical() + " ";
    }

    /// Prints the hunks of `result`.
    ///
    /// @param context  unchanged lines around each change
    /// @param maxHunks maximum number of hunks, or `0` for all
    public void render(DiffResult result, int context, int maxHunks, PrintStream out) {
        int digits = Math.max(3, String.valueOf(Math.max(
                result.leftFile().lineNumber(result.leftFile().lineCount()),
                result.rightFile().lineNumber(result.rightFile().lineCount()))).length());
        int column = (width - separator.length()) / 2;
        int textWidth = column - digits - 3;

        out.println(ansi.bold(pad(fit(result.leftFile().displayName(), column), column))
                + ansi.dim(separator) + ansi.bold(fit(result.rightFile().displayName(), column)));
        String h = glyphs.horizontal();
        out.println(ansi.dim(h.repeat(column + 1) + glyphs.cross() + h.repeat(column + 1)));

        if (result.isIdentical()) {
            out.println(ansi.green("No differences after normalization."));
            return;
        }
        List<Hunk> hunks = result.hunks(context);
        int prevEnd = 0;
        int shown = 0;
        for (Hunk hunk : hunks) {
            if (maxHunks > 0 && shown++ >= maxHunks) {
                out.println(ansi.dim(String.format(Locale.ROOT, "    %s %d more change block(s) not shown",
                        glyphs.fold(), hunks.size() - maxHunks)));
                return;
            }
            fold(hunk.startRow() - prevEnd, out);
            for (int i = hunk.startRow(); i < hunk.endRow(); i++) {
                DiffRow row = result.rows().get(i);
                String left = side(result.left(row), row, row.inline().left(), digits, textWidth, column, true);
                String right = side(result.right(row), row, row.inline().right(), digits, textWidth, column, false);
                out.println(left + ansi.dim(separator) + right);
            }
            prevEnd = hunk.endRow();
        }
        fold(result.rows().size() - prevEnd, out);
    }

    private void fold(int n, PrintStream out) {
        if (n > 0) {
            out.println(ansi.dim(String.format(Locale.ROOT, "    %s %,d unchanged line%s", glyphs.fold(), n,
                    n == 1 ? "" : "s")));
        }
    }

    private String side(ProcessedLine line, DiffRow row, List<Span> highlights, int digits, int textWidth,
                        int column, boolean isLeft) {
        if (line == null) {
            return " ".repeat(column);
        }
        String marker = row.isMoved() ? glyphs.moved() : String.valueOf(row.kind().marker());
        String color = colorFor(row, isLeft);
        String number = String.format(Locale.ROOT, "%" + digits + "d", line.number());
        String text = line.text().replace('\t', ' ');
        String ellipsis = glyphs.ellipsis();
        boolean truncated = text.length() > textWidth;
        int visible = truncated ? Math.max(0, textWidth - ellipsis.length()) : text.length();

        StringBuilder sb = new StringBuilder();
        sb.append(ansi.dim(number)).append(' ').append(ansi.wrap(color, marker)).append(' ');
        String clipped = text.substring(0, visible);
        LineMarkup.runs(clipped, line.masks(), highlights, run -> {
            String chunk = clipped.substring(run.start(), run.end());
            String sgr = color;
            if (run.highlighted()) {
                sgr = sgr.isEmpty() ? "7" : sgr + ";7";
            } else if (run.mask() != null) {
                sgr = sgr.isEmpty() ? "2" : sgr + ";2";
            }
            sb.append(ansi.wrap(sgr, chunk));
        });
        if (truncated) {
            sb.append(ansi.dim(ellipsis));
        }
        int used = digits + 3 + visible + (truncated ? ellipsis.length() : 0);
        sb.append(" ".repeat(Math.max(0, column - used)));
        return sb.toString();
    }

    private static String colorFor(DiffRow row, boolean isLeft) {
        if (row.isMoved()) {
            return "35";
        }
        return switch (row.kind()) {
            case REMOVED -> "31";
            case ADDED -> "32";
            case MODIFIED -> isLeft ? "31" : "32";
            case UNCHANGED -> "";
        };
    }

    private String fit(String s, int max) {
        String e = glyphs.ellipsis();
        return s.length() <= max ? s : e + s.substring(s.length() - max + e.length());
    }

    private static String pad(String s, int max) {
        return s + " ".repeat(Math.max(0, max - s.length()));
    }
}
