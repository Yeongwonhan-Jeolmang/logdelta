// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.report;

import io.github.logdelta.core.Version;
import io.github.logdelta.core.analysis.InsightItem;
import io.github.logdelta.core.folder.FolderComparison;
import io.github.logdelta.core.folder.FolderEntry;
import io.github.logdelta.core.util.JsonWriter;
import io.github.logdelta.core.util.Text;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/// Report output for [FolderComparison]s.
///
/// Supported formats are [ReportFormat#MARKDOWN] (a table for pull requests),
/// [ReportFormat#JSON] and [ReportFormat#JUNIT]. HTML and unified diffs describe a single pair
/// of logs; produce them per file instead.
public final class FolderReports {

    /// Formats that can describe a folder comparison.
    public static final Set<ReportFormat> SUPPORTED =
            EnumSet.of(ReportFormat.MARKDOWN, ReportFormat.JSON, ReportFormat.JUNIT);

    private static final int MAX_PROBLEMS_PER_FILE = 5;
    private static final int MAX_TEXT = 140;

    private FolderReports() {
    }

    /// Writes a folder report.
    ///
    /// @throws IllegalArgumentException if `format` is not in [#SUPPORTED]
    public static void write(FolderComparison c, ReportFormat format, Appendable out) throws IOException {
        switch (format) {
            case MARKDOWN -> markdown(c, out);
            case JSON -> json(c, out);
            case JUNIT -> new JUnitReportWriter().write(c, out);
            default -> throw new IllegalArgumentException(
                    format.displayName() + " is not available for folder comparisons; use markdown, json or junit");
        }
    }

    /// Renders a folder report to a string.
    public static String render(FolderComparison c, ReportFormat format) {
        StringBuilder sb = new StringBuilder();
        try {
            write(c, format, sb);
        } catch (IOException e) {
            throw new IllegalStateException("StringBuilder does not throw", e);
        }
        return sb.toString();
    }

    /// Writes a folder report as UTF-8 to `file`.
    public static void writeFile(FolderComparison c, ReportFormat format, Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            write(c, format, w);
        }
    }

    private static void markdown(FolderComparison c, Appendable out) throws IOException {
        out.append("# Folder comparison\n\n");
        out.append("**Baseline:** `").append(c.leftDir().toString().replace("`", "'")).append("`  \n");
        out.append("**Candidate:** `").append(c.rightDir().toString().replace("`", "'")).append("`\n\n");
        out.append(String.format(Locale.ROOT, "%d file(s): %d changed, %d identical, %d only in baseline, "
                        + "%d only in candidate, %d failed. **%d new problem(s).**%n%n",
                c.entries().size(), c.count(FolderEntry.Status.CHANGED), c.count(FolderEntry.Status.IDENTICAL),
                c.count(FolderEntry.Status.ONLY_LEFT), c.count(FolderEntry.Status.ONLY_RIGHT),
                c.count(FolderEntry.Status.FAILED), c.newProblems()));
        if (c.entries().isEmpty()) {
            out.append("> No files found.\n");
            return;
        }
        out.append("| File | Status | Similarity | Changes | New problems |\n|:---|:---|---:|---:|---:|\n");
        for (FolderEntry e : c.entries()) {
            out.append("| `").append(e.name().replace("`", "'").replace("|", "\\|")).append("` | ")
                    .append(e.status().label()).append(e.message().isEmpty() ? "" : " (" + Text.escapeMarkdown(
                            Text.truncate(e.message(), 60)) + ")").append(" | ")
                    .append(e.compared() ? String.format(Locale.ROOT, "%.1f%%", e.similarity() * 100) : "")
                    .append(" | ").append(e.compared() ? String.valueOf(e.stats().unexpectedChanges()) : "")
                    .append(" | ").append(e.newProblemCount() > 0 ? "**" + e.newProblemCount() + "**" : "0")
                    .append(" |\n");
        }
        out.append('\n');
        for (FolderEntry e : c.entries()) {
            if (e.newProblems().isEmpty()) {
                continue;
            }
            out.append("### `").append(e.name().replace("`", "'")).append("`\n\n");
            int n = 0;
            for (InsightItem p : e.newProblems()) {
                if (n++ >= MAX_PROBLEMS_PER_FILE) {
                    out.append("- _").append(String.valueOf(e.newProblems().size() - MAX_PROBLEMS_PER_FILE))
                            .append(" more..._\n");
                    break;
                }
                out.append("- line ").append(String.valueOf(p.lineNumber())).append(", ")
                        .append(p.severity().label()).append(": `")
                        .append(Text.truncate(p.text().strip(), MAX_TEXT).replace("`", "'")).append("`\n");
            }
            out.append('\n');
        }
        out.append("<sub>Generated by ").append(Version.displayName()).append("</sub>\n");
    }

    private static void json(FolderComparison c, Appendable out) throws IOException {
        try {
            JsonWriter w = new JsonWriter(out, true);
            w.beginObject();
            w.name("schemaVersion").value(JsonReportWriter.SCHEMA_VERSION);
            w.name("kind").value("folder");
            w.name("tool").beginObject().name("name").value(Version.NAME).name("version").value(Version.VERSION)
                    .endObject();
            w.name("left").value(c.leftDir().toString());
            w.name("right").value(c.rightDir().toString());
            w.name("elapsedMillis").value(c.elapsedMillis());
            w.name("newProblems").value(c.newProblems());
            w.name("files").beginArray();
            for (FolderEntry e : c.entries()) {
                w.beginObject();
                w.name("name").value(e.name());
                w.name("left").value(e.left() == null ? null : e.left().toString());
                w.name("right").value(e.right() == null ? null : e.right().toString());
                w.name("status").value(e.status().name().toLowerCase(Locale.ROOT));
                if (!e.message().isEmpty()) {
                    w.name("message").value(e.message());
                }
                if (e.compared()) {
                    w.name("similarity").value(e.similarity());
                    w.name("changes").value(e.stats().unexpectedChanges());
                    w.name("expected").value(e.stats().expected());
                    w.name("resolvedProblems").value(e.resolved());
                }
                w.name("newProblems").beginArray();
                for (InsightItem p : e.newProblems()) {
                    w.beginObject().name("line").value(p.lineNumber())
                            .name("severity").value(p.severity().name().toLowerCase(Locale.ROOT))
                            .name("lines").value(p.lines()).name("text").value(p.text()).endObject();
                }
                w.endArray();
                w.endObject();
            }
            w.endArray();
            w.endObject();
            w.finish();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
