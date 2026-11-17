// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.report;

import io.github.logdelta.core.engine.DiffResult;

import java.io.IOException;

/// Serializes a [DiffResult]. Implementations are stateless and thread-safe.
public interface ReportWriter {

    /// Writes the report to `out`. The appendable is not closed.
    void write(DiffResult result, ReportOptions options, Appendable out) throws IOException;

    /// Convenience method that renders the report to a string.
    default String render(DiffResult result, ReportOptions options) {
        StringBuilder sb = new StringBuilder();
        try {
            write(result, options, sb);
        } catch (IOException e) {
            throw new IllegalStateException("StringBuilder does not throw", e);
        }
        return sb.toString();
    }
}
