// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.report;

import io.github.logdelta.core.engine.DiffResult;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/// Factory and file helpers for report writers.
public final class Reports {

    private Reports() {
    }

    /// Returns the writer for a format.
    public static ReportWriter writer(ReportFormat format) {
        return switch (format) {
            case HTML -> new HtmlReportWriter();
            case JSON -> new JsonReportWriter();
            case MARKDOWN -> new MarkdownReportWriter();
            case UNIFIED -> new UnifiedDiffWriter();
            case JUNIT -> new JUnitReportWriter();
        };
    }

    /// Writes a report as UTF-8 to `file`, replacing it if it exists.
    public static void writeFile(DiffResult result, ReportFormat format, ReportOptions options, Path file)
            throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer(format).write(result, options, w);
        }
    }
}
