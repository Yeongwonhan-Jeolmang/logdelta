// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.report;

import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.Hunk;
import io.github.logdelta.core.model.ProcessedLine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/// Writes changes in the familiar unified diff format (`---`/`+++`/`@@`).
///
/// Original (not normalized) lines are written, so the output reads like a regular diff of the
/// two files. Hunk headers use original line numbers. When ignore patterns removed lines, the
/// counts in a header may not add up to the line-number gap; the output is meant for reading and
/// code review, not for `patch`.
public final class UnifiedDiffWriter implements ReportWriter {

    @Override
    public void write(DiffResult result, ReportOptions options, Appendable out) throws IOException {
        if (result.isIdentical()) {
            return;
        }
        out.append("--- ").append(label(result.leftFile().displayName(), result.leftFile().path())).append('\n');
        out.append("+++ ").append(label(result.rightFile().displayName(), result.rightFile().path())).append('\n');
        List<Hunk> hunks = options.fullContext()
                ? List.of(new Hunk(0, result.rows().size()))
                : result.hunks(options.contextLines());
        int written = 0;
        for (Hunk h : hunks) {
            if (options.maxHunks() > 0 && written++ >= options.maxHunks()) {
                out.append("# ... ").append(String.valueOf(hunks.size() - options.maxHunks()))
                        .append(" more hunk(s) omitted\n");
                break;
            }
            writeHunk(result, h, out);
        }
    }

    private static String label(String name, java.nio.file.Path path) {
        return path != null ? path.toString() : name;
    }

    private static void writeHunk(DiffResult result, Hunk h, Appendable out) throws IOException {
        List<DiffRow> rows = result.rows().subList(h.startRow(), h.endRow());
        int leftStart = -1;
        int rightStart = -1;
        int leftCount = 0;
        int rightCount = 0;
        for (DiffRow r : rows) {
            if (r.hasLeft()) {
                if (leftStart < 0) {
                    leftStart = result.left(r).number();
                }
                leftCount++;
            }
            if (r.hasRight()) {
                if (rightStart < 0) {
                    rightStart = result.right(r).number();
                }
                rightCount++;
            }
        }
        out.append("@@ -").append(range(leftStart, leftCount)).append(" +").append(range(rightStart, rightCount))
                .append(" @@\n");

        // Within a run of changes, unified format lists all removals before all additions.
        List<String> minus = new ArrayList<>();
        List<String> plus = new ArrayList<>();
        for (DiffRow r : rows) {
            if (!r.isChange()) {
                flush(minus, plus, out);
                out.append(' ').append(result.left(r).text()).append('\n');
                continue;
            }
            ProcessedLine l = result.left(r);
            ProcessedLine rr = result.right(r);
            if (l != null) {
                minus.add(l.text());
            }
            if (rr != null) {
                plus.add(rr.text());
            }
        }
        flush(minus, plus, out);
    }

    private static void flush(List<String> minus, List<String> plus, Appendable out) throws IOException {
        for (String s : minus) {
            out.append('-').append(s).append('\n');
        }
        for (String s : plus) {
            out.append('+').append(s).append('\n');
        }
        minus.clear();
        plus.clear();
    }

    private static String range(int start, int count) {
        if (start < 0) {
            return "0,0";
        }
        return count == 1 ? Integer.toString(start) : start + "," + count;
    }
}
