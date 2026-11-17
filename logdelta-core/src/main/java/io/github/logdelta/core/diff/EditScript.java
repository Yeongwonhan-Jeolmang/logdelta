// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.diff;

import java.util.ArrayList;
import java.util.List;

/// Accumulates edits in order and merges adjacent operations of the same type.
///
/// Algorithms emit operations front to back; this class keeps the resulting script compact.
final class EditScript {

    private final List<Edit> edits = new ArrayList<>();

    void equal(int aStart, int bStart, int length) {
        if (length > 0) {
            add(new Edit(Edit.Type.EQUAL, aStart, aStart + length, bStart, bStart + length));
        }
    }

    void delete(int aStart, int aEnd, int bPos) {
        if (aEnd > aStart) {
            add(new Edit(Edit.Type.DELETE, aStart, aEnd, bPos, bPos));
        }
    }

    void insert(int aPos, int bStart, int bEnd) {
        if (bEnd > bStart) {
            add(new Edit(Edit.Type.INSERT, aPos, aPos, bStart, bEnd));
        }
    }

    private void add(Edit e) {
        if (!edits.isEmpty()) {
            Edit last = edits.getLast();
            if (last.type() == e.type() && last.aEnd() == e.aStart() && last.bEnd() == e.bStart()) {
                edits.removeLast();
                edits.add(new Edit(e.type(), last.aStart(), e.aEnd(), last.bStart(), e.bEnd()));
                return;
            }
        }
        edits.add(e);
    }

    List<Edit> edits() {
        return List.copyOf(edits);
    }
}
