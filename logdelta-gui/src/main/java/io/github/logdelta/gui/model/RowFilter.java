// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.model;

import io.github.logdelta.core.analysis.InsightItem;
import io.github.logdelta.core.analysis.Severity;
import io.github.logdelta.core.analysis.SeverityDetector;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.ProcessedLine;

import java.util.List;
import java.util.Locale;

/// Evaluates a [ViewFilter] against every row of a result.
final class RowFilter {

    private RowFilter() {
    }

    /// One flag per diff row: whether it passes `filter`.
    static boolean[] matches(DiffResult r, ViewFilter filter) {
        List<DiffRow> rows = r.rows();
        boolean[] keep = new boolean[rows.size()];
        switch (filter.mode()) {
            case ALL -> java.util.Arrays.fill(keep, true);
            case CHANGES -> {
                for (int i = 0; i < keep.length; i++) {
                    keep[i] = rows.get(i).isChange();
                }
            }
            case PROBLEMS -> {
                for (InsightItem p : r.insights().newProblems()) {
                    for (int i = p.row(); i < Math.min(keep.length, p.row() + p.lines()); i++) {
                        keep[i] = true;
                    }
                }
            }
            case ERRORS -> {
                for (int i = 0; i < keep.length; i++) {
                    DiffRow d = rows.get(i);
                    keep[i] = isError(r.left(d)) || isError(r.right(d));
                }
            }
        }
        String needle = filter.needle();
        if (!needle.isEmpty()) {
            for (int i = 0; i < keep.length; i++) {
                if (keep[i]) {
                    DiffRow d = rows.get(i);
                    keep[i] = contains(r.left(d), needle) || contains(r.right(d), needle);
                }
            }
        }
        return keep;
    }

    private static boolean isError(ProcessedLine line) {
        return line != null && SeverityDetector.detect(line.text()).compareTo(Severity.ERROR) >= 0;
    }

    private static boolean contains(ProcessedLine line, String needle) {
        return line != null && line.text().toLowerCase(Locale.ROOT).contains(needle);
    }
}
