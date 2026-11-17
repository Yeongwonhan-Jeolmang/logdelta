// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.render;

import io.github.logdelta.core.Version;
import io.github.logdelta.core.analysis.InsightItem;
import io.github.logdelta.core.folder.FolderComparison;
import io.github.logdelta.core.folder.FolderEntry;
import io.github.logdelta.core.util.LogText;

import java.io.PrintStream;
import java.util.Locale;

/// Prints a [FolderComparison] as a table, files needing attention first.
public final class FolderRenderer {

    private static final int MAX_PROBLEMS_PER_FILE = 3;

    private final Ansi ansi;
    private final Glyphs glyphs;
    private final int width;

    /// @param ansi   styling
    /// @param glyphs symbol characters
    /// @param width  terminal width used to truncate long names
    public FolderRenderer(Ansi ansi, Glyphs glyphs, int width) {
        this.ansi = ansi;
        this.glyphs = glyphs;
        this.width = Math.max(60, width);
    }

    /// Prints the table.
    public void render(FolderComparison c, PrintStream out) {
        out.println(ansi.bold(Version.NAME) + "  " + c.leftDir() + ansi.dim(" " + glyphs.arrow() + " ") + c.rightDir());
        out.println();
        out.printf(Locale.ROOT, "  %d file(s): %s, %s, %s, %s%s%n", c.entries().size(),
                ansi.yellow(c.count(FolderEntry.Status.CHANGED) + " changed"),
                c.count(FolderEntry.Status.IDENTICAL) + " identical",
                ansi.red(c.count(FolderEntry.Status.ONLY_LEFT) + " only in baseline"),
                ansi.green(c.count(FolderEntry.Status.ONLY_RIGHT) + " only in candidate"),
                c.count(FolderEntry.Status.FAILED) > 0
                        ? ", " + ansi.red(c.count(FolderEntry.Status.FAILED) + " failed") : "");
        if (c.entries().isEmpty()) {
            out.println();
            out.println(ansi.dim("  No files found."));
            return;
        }
        out.println();
        out.println(ansi.dim(String.format(Locale.ROOT, "  %4s  %7s  %6s  %-17s  %s", "NEW", "SIMIL.", "CHG",
                "STATUS", "FILE")));
        int nameRoom = Math.max(20, width - 46);
        for (FolderEntry e : c.entries()) {
            String problems = String.format(Locale.ROOT, "%4s", e.newProblemCount() == 0 ? "" : e.newProblemCount());
            String sim = e.compared() ? String.format(Locale.ROOT, "%6.1f%%", e.similarity() * 100) : "       ";
            String chg = e.compared() ? String.format(Locale.ROOT, "%6d", e.stats().unexpectedChanges()) : "      ";
            String status = String.format(Locale.ROOT, "%-17s", e.status().label());
            status = switch (e.status()) {
                case CHANGED -> ansi.yellow(status);
                case ONLY_LEFT, FAILED -> ansi.red(status);
                case ONLY_RIGHT -> ansi.green(status);
                default -> ansi.dim(status);
            };
            String name = truncate(e.name().replace("\u2192", glyphs.arrow()), nameRoom);
            if (!e.message().isEmpty()) {
                name += ansi.dim(" (" + e.message() + ")");
            }
            out.println("  " + ansi.red(problems) + "  " + sim + "  " + chg + "  " + status + "  " + name);
        }
        out.println();
        for (FolderEntry e : c.entries()) {
            if (e.newProblems().isEmpty()) {
                continue;
            }
            out.println(ansi.bold(e.name().replace("\u2192", glyphs.arrow())) + " "
                    + ansi.dim("(" + e.newProblemCount() + " new)"));
            int n = 0;
            for (InsightItem p : e.newProblems()) {
                if (n++ >= MAX_PROBLEMS_PER_FILE) {
                    out.println(ansi.dim("  " + glyphs.fold() + " " + (e.newProblemCount() - MAX_PROBLEMS_PER_FILE)
                            + " more"));
                    break;
                }
                out.println("  " + ansi.red(String.format(Locale.ROOT, "%-5s", p.severity().label())) + " "
                        + ansi.dim(String.format(Locale.ROOT, "L%-6d", p.lineNumber())) + " "
                        + truncate(LogText.message(p.text()), Math.max(20, width - 16)));
            }
            out.println();
        }
        out.println(ansi.dim(String.format(Locale.ROOT, "  %d new problem(s) in %d ms", c.newProblems(),
                c.elapsedMillis())));
    }

    private String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, max - glyphs.ellipsis().length())) + glyphs.ellipsis();
    }
}
