// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.model;

import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.Hunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Maps the rows of a [DiffResult] to visual rows, collapsing long unchanged regions into
/// folds that can be expanded one by one, or, with an active [ViewFilter], keeping only the
/// rows that pass it (with [ViewRow.Gap]s where rows are hidden).
///
/// Not thread-safe; used on the event dispatch thread only.
public final class DiffViewModel {

    /// Unchanged runs shorter than this are never folded: a fold row would save almost nothing.
    static final int MIN_FOLD = 4;

    private final DiffResult result;
    private final boolean collapse;
    private final int context;
    private final ViewFilter filter;
    private final Set<Integer> expanded = new HashSet<>();
    private List<ViewRow> rows = List.of();
    private int[] viewOfRow = new int[0];
    private int[] changeStarts = new int[0];
    private int visibleRows;

    /// @param result   comparison to display
    /// @param collapse whether to fold unchanged regions
    /// @param context  unchanged rows kept visible around each change
    public DiffViewModel(DiffResult result, boolean collapse, int context) {
        this(result, collapse, context, ViewFilter.NONE);
    }

    /// @param result   comparison to display
    /// @param collapse whether to fold unchanged regions (ignored while `filter` is active)
    /// @param context  unchanged rows kept visible around each change
    /// @param filter   which rows to show
    public DiffViewModel(DiffResult result, boolean collapse, int context, ViewFilter filter) {
        this.result = result;
        this.collapse = collapse;
        this.context = Math.max(0, context);
        this.filter = filter;
        rebuild();
    }

    /// The active filter.
    public ViewFilter filter() {
        return filter;
    }

    /// Number of diff rows that pass the filter (all rows when no filter is active).
    public int visibleRowCount() {
        return visibleRows;
    }

    /// The underlying comparison.
    public DiffResult result() {
        return result;
    }

    /// Number of visual rows.
    public int size() {
        return rows.size();
    }

    /// The visual row at `index`.
    public ViewRow get(int index) {
        return rows.get(index);
    }

    /// The diff row shown at a view index, or `null` for folds and gaps.
    public DiffRow diffRow(int viewIndex) {
        return rows.get(viewIndex) instanceof ViewRow.Line(int row) ? result.rows().get(row) : null;
    }

    /// View index showing diff row `row`, or `-1` if it is folded away.
    public int viewIndexOf(int row) {
        return row >= 0 && row < viewOfRow.length ? viewOfRow[row] : -1;
    }

    /// Makes sure diff row `row` is visible, expanding its fold if necessary.
    ///
    /// @return the view index of the row
    public int reveal(int row) {
        int v = viewIndexOf(row);
        if (v >= 0) {
            return v;
        }
        for (ViewRow r : rows) {
            if (r instanceof ViewRow.Fold fold && fold.contains(row)) {
                expand(fold.start());
                return viewIndexOf(row);
            }
        }
        return -1;
    }

    /// Expands the fold that starts at diff row `foldStart`.
    public void expand(int foldStart) {
        if (expanded.add(foldStart)) {
            rebuild();
        }
    }

    /// Expands every fold.
    public void expandAll() {
        for (ViewRow r : rows) {
            if (r instanceof ViewRow.Fold fold) {
                expanded.add(fold.start());
            }
        }
        rebuild();
    }

    /// Whether any fold is present.
    public boolean hasFolds() {
        return rows.stream().anyMatch(r -> r instanceof ViewRow.Fold);
    }

    /// View indices of the first row of each block of changes, ascending.
    public int[] changeStarts() {
        return changeStarts.clone();
    }

    /// The next change block start after `viewIndex`, or `-1`.
    public int nextChange(int viewIndex) {
        for (int s : changeStarts) {
            if (s > viewIndex) {
                return s;
            }
        }
        return -1;
    }

    /// The previous change block start before `viewIndex`, or `-1`.
    public int previousChange(int viewIndex) {
        for (int i = changeStarts.length - 1; i >= 0; i--) {
            if (changeStarts[i] < viewIndex) {
                return changeStarts[i];
            }
        }
        return -1;
    }

    private void rebuild() {
        List<DiffRow> all = result.rows();
        List<ViewRow> out = new ArrayList<>(Math.min(all.size(), 4096));
        if (filter.active()) {
            boolean[] keep = RowFilter.matches(result, filter);
            int gapStart = -1;
            int kept = 0;
            for (int i = 0; i < all.size(); i++) {
                if (keep[i]) {
                    if (gapStart >= 0) {
                        out.add(new ViewRow.Gap(gapStart, i));
                        gapStart = -1;
                    }
                    out.add(new ViewRow.Line(i));
                    kept++;
                } else if (gapStart < 0) {
                    gapStart = i;
                }
            }
            if (gapStart >= 0) {
                out.add(new ViewRow.Gap(gapStart, all.size()));
            }
            visibleRows = kept;
        } else if (!collapse) {
            for (int i = 0; i < all.size(); i++) {
                out.add(new ViewRow.Line(i));
            }
        } else {
            int prevEnd = 0;
            for (Hunk h : result.hunks(context)) {
                addGap(out, prevEnd, h.startRow());
                for (int i = h.startRow(); i < h.endRow(); i++) {
                    out.add(new ViewRow.Line(i));
                }
                prevEnd = h.endRow();
            }
            addGap(out, prevEnd, all.size());
        }
        if (!filter.active()) {
            visibleRows = all.size();
        }
        rows = out;
        viewOfRow = new int[all.size()];
        Arrays.fill(viewOfRow, -1);
        List<Integer> starts = new ArrayList<>();
        for (int v = 0; v < out.size(); v++) {
            if (!(out.get(v) instanceof ViewRow.Line(int row))) {
                continue;
            }
            viewOfRow[row] = v;
            boolean change = all.get(row).isChange();
            boolean prevChange = v > 0 && out.get(v - 1) instanceof ViewRow.Line(int prev) && all.get(prev).isChange();
            if (change && !prevChange) {
                starts.add(v);
            }
        }
        changeStarts = starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private void addGap(List<ViewRow> out, int from, int to) {
        if (to - from < MIN_FOLD || expanded.contains(from)) {
            for (int i = from; i < to; i++) {
                out.add(new ViewRow.Line(i));
            }
        } else {
            out.add(new ViewRow.Fold(from, to));
        }
    }
}
