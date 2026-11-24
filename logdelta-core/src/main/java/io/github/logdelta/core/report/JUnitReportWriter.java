// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.report;

import io.github.logdelta.core.Version;
import io.github.logdelta.core.analysis.InsightItem;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.folder.FolderComparison;
import io.github.logdelta.core.folder.FolderEntry;
import io.github.logdelta.core.model.DiffStats;
import io.github.logdelta.core.timing.PhaseChange;
import io.github.logdelta.core.timing.Timeline;
import io.github.logdelta.core.util.LogText;
import io.github.logdelta.core.util.Text;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/// Writes JUnit-style XML so that CI systems (GitHub Actions, GitLab, Jenkins, Azure
/// Pipelines, ...) can show log regressions in their test views.
///
/// The mapping follows the `check` command's rule that only *new problems* fail a build:
///
/// - Every new problem becomes a failing `<testcase>`, named after its message, so each one
///   is listed separately and tracked across builds.
/// - A passing `<testcase>` named `no new problems` is written when there are none, so the
///   suite is never empty.
/// - Similarity, change counts and slower phases are written as `<properties>` and to
///   `<system-out>`; they inform but never fail.
///
/// For a [FolderComparison] each file becomes its own `<testsuite>`.
public final class JUnitReportWriter implements ReportWriter {

    private static final int MAX_NAME = 120;

    @Override
    public void write(DiffResult r, ReportOptions options, Appendable out) throws IOException {
        List<InsightItem> problems = r.insights().newProblems();
        int tests = Math.max(1, problems.size());
        double seconds = r.elapsedMillis() / 1000.0;
        header(out, tests, problems.size(), seconds);
        String suite = r.rightFile().displayName();
        out.append("  <testsuite name=\"").append(attr("logdelta: " + suite)).append("\" tests=\"")
                .append(String.valueOf(tests)).append("\" failures=\"").append(String.valueOf(problems.size()))
                .append("\" errors=\"0\" skipped=\"0\" time=\"").append(secs(seconds)).append("\">\n");
        properties(out, r);
        testcases(out, className(suite), problems);
        systemOut(out, r);
        out.append("  </testsuite>\n</testsuites>\n");
    }

    /// Writes a report for a folder comparison: one suite per file.
    public void write(FolderComparison c, Appendable out) throws IOException {
        int tests = 0;
        int failures = 0;
        int errors = 0;
        for (FolderEntry e : c.entries()) {
            tests += Math.max(1, e.newProblemCount());
            failures += e.newProblemCount();
            errors += e.status() == FolderEntry.Status.FAILED ? 1 : 0;
        }
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<testsuites name=\"logdelta\" tests=\"").append(String.valueOf(tests))
                .append("\" failures=\"").append(String.valueOf(failures)).append("\" errors=\"")
                .append(String.valueOf(errors)).append("\" time=\"").append(secs(c.elapsedMillis() / 1000.0))
                .append("\">\n");
        for (FolderEntry e : c.entries()) {
            int t = Math.max(1, e.newProblemCount());
            boolean skipped = e.status() == FolderEntry.Status.SKIPPED;
            boolean error = e.status() == FolderEntry.Status.FAILED;
            out.append("  <testsuite name=\"").append(attr("logdelta: " + e.name())).append("\" tests=\"")
                    .append(String.valueOf(t)).append("\" failures=\"").append(String.valueOf(e.newProblemCount()))
                    .append("\" errors=\"").append(error ? "1" : "0").append("\" skipped=\"")
                    .append(skipped ? "1" : "0").append("\">\n");
            out.append("    <properties>\n");
            property(out, "status", e.status().label());
            if (e.compared()) {
                property(out, "similarity", String.format(Locale.ROOT, "%.4f", e.similarity()));
                property(out, "changes", String.valueOf(e.stats().unexpectedChanges()));
            }
            out.append("    </properties>\n");
            String cls = className(e.name());
            if (error) {
                out.append("    <testcase classname=\"").append(attr(cls)).append("\" name=\"compare\">\n")
                        .append("      <error type=\"ComparisonError\" message=\"").append(attr(e.message()))
                        .append("\"/>\n    </testcase>\n");
            } else if (skipped) {
                out.append("    <testcase classname=\"").append(attr(cls)).append("\" name=\"compare\">\n")
                        .append("      <skipped message=\"").append(attr(e.message())).append("\"/>\n")
                        .append("    </testcase>\n");
            } else {
                testcases(out, cls, e.newProblems());
            }
            out.append("  </testsuite>\n");
        }
        out.append("</testsuites>\n");
    }

    private static void header(Appendable out, int tests, int failures, double seconds) throws IOException {
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<!-- ").append(Version.displayName()).append(" -->\n");
        out.append("<testsuites name=\"logdelta\" tests=\"").append(String.valueOf(tests)).append("\" failures=\"")
                .append(String.valueOf(failures)).append("\" errors=\"0\" time=\"").append(secs(seconds))
                .append("\">\n");
    }

    private static void properties(Appendable out, DiffResult r) throws IOException {
        DiffStats s = r.stats();
        out.append("    <properties>\n");
        property(out, "baseline", r.leftFile().displayName());
        for (var extra : r.extraBaselines()) {
            property(out, "baseline", extra.displayName());
        }
        property(out, "candidate", r.rightFile().displayName());
        property(out, "similarity", String.format(Locale.ROOT, "%.4f", s.similarity()));
        property(out, "modified", String.valueOf(s.modified()));
        property(out, "removed", String.valueOf(s.removed() - s.moved()));
        property(out, "added", String.valueOf(s.added() - s.moved()));
        property(out, "moved", String.valueOf(s.moved()));
        property(out, "expected", String.valueOf(s.expected()));
        property(out, "resolved-problems", String.valueOf(r.insights().resolvedProblems().size()));
        out.append("    </properties>\n");
    }

    private static void property(Appendable out, String name, String value) throws IOException {
        out.append("      <property name=\"").append(attr(name)).append("\" value=\"").append(attr(value))
                .append("\"/>\n");
    }

    private static void testcases(Appendable out, String cls, List<InsightItem> problems) throws IOException {
        if (problems.isEmpty()) {
            out.append("    <testcase classname=\"").append(attr(cls)).append("\" name=\"no new problems\"/>\n");
            return;
        }
        for (InsightItem p : problems) {
            String message = LogText.message(p.text()).strip();
            String name = p.severity().label() + " at line " + p.lineNumber() + ": "
                    + Text.truncate(message.isEmpty() ? p.text().strip() : message, MAX_NAME);
            out.append("    <testcase classname=\"").append(attr(cls)).append("\" name=\"").append(attr(name))
                    .append("\">\n");
            out.append("      <failure type=\"NewProblem\" message=\"").append(attr("New " + p.severity().label()
                    + " in candidate line " + p.lineNumber() + (p.lines() > 1 ? " (+" + (p.lines() - 1)
                    + " lines)" : "") + (p.repeated() ? "; " + p.occurrences() + " occurrences of " + p.template()
                    : ""))).append("\">").append(Text.escapeXml(p.text())).append("</failure>\n");
            out.append("    </testcase>\n");
        }
    }

    private static void systemOut(Appendable out, DiffResult r) throws IOException {
        DiffStats s = r.stats();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Similarity %.1f%%, %d unexpected change(s), %d expected.%n",
                s.similarity() * 100, s.unexpectedChanges(), s.expected()));
        if (r.timing().available()) {
            sb.append("Duration ").append(Timeline.format(r.timing().left().duration())).append(" -> ")
                    .append(Timeline.format(r.timing().right().duration())).append('\n');
            for (PhaseChange p : r.timing().slower()) {
                sb.append("Slower: ").append(LogText.message(p.startText()).strip()).append(" -> ")
                        .append(LogText.message(p.endText()).strip()).append(": ")
                        .append(Timeline.format(p.leftMillis())).append(" -> ")
                        .append(Timeline.format(p.rightMillis())).append('\n');
            }
        }
        out.append("    <system-out>").append(Text.escapeXml(sb)).append("</system-out>\n");
    }

    private static String className(String fileName) {
        return "logdelta." + fileName.replaceAll("[^A-Za-z0-9_.-]+", "_");
    }

    private static String attr(String s) {
        return Text.escapeXml(s.replace("\n", " ").replace("\r", " ").replace("\t", " "));
    }

    private static String secs(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }
}
