// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.report;

import io.github.logdelta.core.Version;
import io.github.logdelta.core.analysis.FrequencyShift;
import io.github.logdelta.core.analysis.InsightItem;
import io.github.logdelta.core.analysis.Insights;
import io.github.logdelta.core.analysis.Severity;
import io.github.logdelta.core.diff.Span;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.DiffStats;
import io.github.logdelta.core.model.Hunk;
import io.github.logdelta.core.model.ProcessedLine;
import io.github.logdelta.core.normalize.Rule;
import io.github.logdelta.core.suggest.RuleSuggestion;
import io.github.logdelta.core.timing.PhaseChange;
import io.github.logdelta.core.timing.TimingAnalysis;
import io.github.logdelta.core.util.JsonWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;

/// Writes a machine-readable report, suitable for CI pipelines and further tooling.
///
/// The schema is documented in `docs/REPORTS.md`. Top-level members: `tool`,
/// `left`, `right`, `options`, `stats`, `insights` and
/// `hunks`. Each hunk row carries its kind, both line numbers and texts, and inline
/// highlight spans.
public final class JsonReportWriter implements ReportWriter {

    /// Version of the JSON layout; incremented on incompatible changes.
    public static final int SCHEMA_VERSION = 1;

    @Override
    public void write(DiffResult r, ReportOptions options, Appendable out) throws IOException {
        try {
            JsonWriter w = new JsonWriter(out, true);
            w.beginObject();
            w.name("schemaVersion").value(SCHEMA_VERSION);
            w.name("tool").beginObject().name("name").value(Version.NAME).name("version").value(Version.VERSION)
                    .endObject();
            file(w, "left", r.leftFile());
            file(w, "right", r.rightFile());
            w.name("extraBaselines").beginArray();
            for (LogFile f : r.extraBaselines()) {
                w.beginObject().name("name").value(f.displayName())
                        .name("path").value(f.path() == null ? null : f.path().toString())
                        .name("lines").value(f.lineCount()).endObject();
            }
            w.endArray();
            options(w, r);
            stats(w, r.stats(), r.elapsedMillis());
            insights(w, r);
            timing(w, r);
            suggestions(w, r);
            hunks(w, r, options);
            w.endObject();
            w.finish();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static void file(JsonWriter w, String name, LogFile f) {
        w.name(name).beginObject()
                .name("name").value(f.displayName())
                .name("path").value(f.path() == null ? null : f.path().toString())
                .name("lines").value(f.lineCount())
                .name("bytes").value(f.sizeBytes())
                .name("charset").value(f.charset().name())
                .name("gzip").value(f.compressed())
                .endObject();
    }

    private static void options(JsonWriter w, DiffResult r) {
        var o = r.options();
        w.name("options").beginObject()
                .name("algorithm").value(o.algorithm().key())
                .name("minimal").value(o.minimal())
                .name("ignoreCase").value(o.ignoreCase())
                .name("ignoreWhitespace").value(o.ignoreWhitespace())
                .name("detectMoves").value(o.detectMoves())
                .name("multilineEntries").value(o.multilineEntries());
        w.name("ignorePatterns").beginArray();
        o.ignorePatterns().forEach(w::value);
        w.endArray();
        w.name("enabledRules").beginArray();
        for (Rule rule : o.rules().enabledRules()) {
            w.value(rule.id());
        }
        w.endArray();
        w.endObject();
    }

    private static void stats(JsonWriter w, DiffStats s, long elapsed) {
        w.name("stats").beginObject()
                .name("identical").value(s.identical())
                .name("similarity").value(Math.round(s.similarity() * 10000) / 10000.0)
                .name("unchanged").value(s.unchanged())
                .name("modified").value(s.modified())
                .name("removed").value(s.removed())
                .name("added").value(s.added())
                .name("moved").value(s.moved())
                .name("expected").value(s.expected())
                .name("leftIgnored").value(s.leftIgnored())
                .name("rightIgnored").value(s.rightIgnored())
                .name("leftMasked").value(s.leftMasked())
                .name("rightMasked").value(s.rightMasked())
                .name("elapsedMillis").value(elapsed)
                .endObject();
    }

    private static void insights(JsonWriter w, DiffResult r) {
        Insights in = r.insights();
        w.name("insights").beginObject();
        if (in.firstDivergenceRow() >= 0) {
            DiffRow row = r.rows().get(in.firstDivergenceRow());
            w.name("firstDivergence").beginObject()
                    .name("row").value(in.firstDivergenceRow())
                    .name("leftLine").value(row.hasLeft() ? r.left(row).number() : -1)
                    .name("rightLine").value(row.hasRight() ? r.right(row).number() : -1)
                    .endObject();
        } else {
            w.name("firstDivergence").nullValue();
        }
        items(w, "newProblems", in.newProblems());
        items(w, "resolvedProblems", in.resolvedProblems());
        w.name("severity").beginObject();
        for (Severity s : Severity.values()) {
            if (s == Severity.NONE) {
                continue;
            }
            w.name(s.name().toLowerCase(Locale.ROOT)).beginObject()
                    .name("left").value(in.leftCount(s)).name("right").value(in.rightCount(s)).endObject();
        }
        w.endObject();
        w.name("frequencyShifts").beginArray();
        for (FrequencyShift f : in.frequencyShifts()) {
            w.beginObject().name("template").value(f.template()).name("left").value(f.leftCount())
                    .name("right").value(f.rightCount()).name("delta").value(f.delta()).endObject();
        }
        w.endArray();
        w.endObject();
    }

    private static void timing(JsonWriter w, DiffResult r) {
        TimingAnalysis t = r.timing();
        w.name("timing").beginObject().name("available").value(t.available());
        if (t.available()) {
            w.name("leftDurationMillis").value(t.left().duration())
                    .name("rightDurationMillis").value(t.right().duration());
        }
        phases(w, "slower", r, t.slower());
        phases(w, "faster", r, t.faster());
        w.endObject();
    }

    private static void phases(JsonWriter w, String name, DiffResult r, List<PhaseChange> phases) {
        w.name(name).beginArray();
        for (PhaseChange p : phases) {
            DiffRow start = r.rows().get(p.startRow());
            DiffRow end = r.rows().get(p.endRow());
            w.beginObject()
                    .name("leftMillis").value(p.leftMillis())
                    .name("rightMillis").value(p.rightMillis())
                    .name("from").beginObject().name("leftLine").value(r.left(start).number())
                    .name("rightLine").value(r.right(start).number()).name("text").value(p.startText()).endObject()
                    .name("to").beginObject().name("leftLine").value(r.left(end).number())
                    .name("rightLine").value(r.right(end).number()).name("text").value(p.endText()).endObject()
                    .endObject();
        }
        w.endArray();
    }

    private static void suggestions(JsonWriter w, DiffResult r) {
        w.name("suggestedRules").beginArray();
        for (RuleSuggestion s : r.suggestions()) {
            w.beginObject().name("id").value(s.rule().id()).name("summary").value(s.summary())
                    .name("pattern").value(s.rule().regex()).name("replacement").value(s.rule().replacement())
                    .name("resolves").value(s.resolves()).endObject();
        }
        w.endArray();
    }

    private static void items(JsonWriter w, String name, List<InsightItem> items) {
        w.name(name).beginArray();
        for (InsightItem i : items) {
            w.beginObject().name("line").value(i.lineNumber()).name("severity").value(i.severity().name())
                    .name("text").value(i.text()).name("groupedLines").value(i.lines()).endObject();
        }
        w.endArray();
    }

    private static void hunks(JsonWriter w, DiffResult r, ReportOptions options) {
        List<Hunk> hunks = r.hunks(options.contextLines());
        if (options.maxHunks() > 0 && hunks.size() > options.maxHunks()) {
            hunks = hunks.subList(0, options.maxHunks());
        }
        w.name("hunks").beginArray();
        for (Hunk h : hunks) {
            w.beginObject().name("rows").beginArray();
            for (int i = h.startRow(); i < h.endRow(); i++) {
                DiffRow row = r.rows().get(i);
                w.beginObject().name("kind").value(row.kind().name().toLowerCase(Locale.ROOT));
                if (row.expected()) {
                    w.name("expected").value(true);
                }
                side(w, "left", r.left(row), row.inline().left());
                side(w, "right", r.right(row), row.inline().right());
                if (row.isMoved()) {
                    DiffRow peer = r.rows().get(row.movedPeer());
                    ProcessedLine pl = peer.hasLeft() ? r.left(peer) : r.right(peer);
                    w.name("movedFromOrToLine").value(pl.number());
                }
                w.endObject();
            }
            w.endArray().endObject();
        }
        w.endArray();
    }

    private static void side(JsonWriter w, String name, ProcessedLine line, List<Span> spans) {
        if (line == null) {
            w.name(name).nullValue();
            return;
        }
        w.name(name).beginObject().name("line").value(line.number()).name("text").value(line.text());
        if (!line.normalized().equals(line.text())) {
            w.name("normalized").value(line.normalized());
        }
        if (!spans.isEmpty()) {
            w.name("changed").beginArray();
            for (Span s : spans) {
                w.beginArray().value(s.start()).value(s.end()).endArray();
            }
            w.endArray();
        }
        w.endObject();
    }
}
