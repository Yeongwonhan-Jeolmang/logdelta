// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.command;

import io.github.logdelta.cli.CliContext;
import io.github.logdelta.cli.args.UsageException;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.folder.FolderComparison;
import io.github.logdelta.core.report.FolderReports;
import io.github.logdelta.core.report.ReportFormat;
import io.github.logdelta.core.report.ReportOptions;
import io.github.logdelta.core.report.Reports;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/// Writing of report files named on the command line (`--report FILE`).
final class Outputs {

    private Outputs() {
    }

    /// The result as it may leave the machine: secrets removed according to the options (see
    /// `--no-redact` and `--redact`). Tells the user on standard error how many were removed.
    static DiffResult exportable(DiffResult r, CliContext ctx, boolean quiet) {
        DiffResult red = r.redacted(r.options().redactor());
        if (red.redactedSecrets() > 0 && !quiet) {
            ctx.err().println("logdelta: removed " + red.redactedSecrets()
                    + " secret(s) from the report (use --no-redact to keep them)");
        }
        return red;
    }

    /// Warns when a time window left nothing of a log to compare: two empty logs are "identical",
    /// which would otherwise let a mistyped `--since` pass a CI check silently.
    static void warnAboutWindow(DiffResult r, CliContext ctx, boolean quiet) {
        if (quiet) {
            return;
        }
        for (var f : List.of(r.leftFile(), r.rightFile())) {
            if (f.window() != null && f.lines().isEmpty()) {
                ctx.err().println("logdelta: warning: no line of " + f.displayName() + " is inside the time window "
                        + f.window().describe());
            }
        }
    }

    /// Creates the folder an output file goes into, like report files do.
    static void createParent(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
    }

    /// The report format implied by a file name, or `null` if the extension is unknown.
    static ReportFormat formatOf(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".htm")) {
            return ReportFormat.HTML;
        }
        if (name.endsWith(".patch")) {
            return ReportFormat.UNIFIED;
        }
        if (name.endsWith(".markdown")) {
            return ReportFormat.MARKDOWN;
        }
        for (ReportFormat rf : ReportFormat.values()) {
            if (name.endsWith("." + rf.extension())) {
                return rf;
            }
        }
        return null;
    }

    /// Validates a `--report` argument: `FILE` or `FORMAT:FILE`.
    static Target target(String spec, CliContext ctx) throws UsageException {
        int colon = spec.indexOf(':');
        // A single letter before the colon is a Windows drive, not a format.
        if (colon > 1) {
            try {
                ReportFormat f = ReportFormat.parse(spec.substring(0, colon));
                return new Target(f, ctx.resolve(spec.substring(colon + 1)));
            } catch (IllegalArgumentException _) {
                // not a format prefix; treat the whole thing as a path
            }
        }
        ReportFormat f = formatOf(spec);
        if (f == null) {
            throw new UsageException("Cannot tell the report format of '" + spec
                    + "'; use .html, .json, .md, .xml or .diff, or write FORMAT:FILE");
        }
        return new Target(f, ctx.resolve(spec));
    }

    /// A report file to write.
    record Target(ReportFormat format, Path file) {

        void write(DiffResult r, ReportOptions options, CliContext ctx) throws IOException {
            Reports.writeFile(r, format, options, file);
            ctx.err().println("Wrote " + format.displayName() + " to " + file);
        }

        void write(FolderComparison c, CliContext ctx) throws IOException, UsageException {
            if (!FolderReports.SUPPORTED.contains(format)) {
                throw new UsageException(format.displayName()
                        + " is not available for folder comparisons; use .md, .json or .xml");
            }
            FolderReports.writeFile(c, format, file);
            ctx.err().println("Wrote " + format.displayName() + " to " + file);
        }
    }
}
