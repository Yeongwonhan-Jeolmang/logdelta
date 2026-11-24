// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.structured;

import io.github.logdelta.core.diff.InlineHighlights;
import io.github.logdelta.core.diff.Span;
import io.github.logdelta.core.model.ProcessedLine;
import io.github.logdelta.core.model.ProcessedLine.StructuredValue;
import io.github.logdelta.core.normalize.Mask;
import io.github.logdelta.core.normalize.NormalizedText;
import io.github.logdelta.core.normalize.Normalizer;
import io.github.logdelta.core.timing.TimestampParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/// Field-by-field handling of structured (JSON and logfmt) lines: building the comparison key,
/// highlighting changed fields and describing what changed.
public final class StructuredLines {

    /// Id recorded on the masks that hide ignored fields.
    public static final String IGNORED_FIELD_RULE = "ignored-field";
    /// Replacement text shown for ignored fields in normalized text.
    public static final String IGNORED = "<IGNORED>";
    /// Field names that usually hold the message.
    public static final List<String> MESSAGE_FIELDS = List.of("msg", "message", "event", "text", "log",
            "@message", "body");
    /// Field names that usually hold the severity.
    public static final List<String> LEVEL_FIELDS = List.of("level", "severity", "lvl", "log.level", "loglevel",
            "@level", "levelname");
    /// Field names that usually hold the time, in order of preference.
    public static final List<String> TIME_FIELDS = List.of("@timestamp", "timestamp", "time", "ts", "datetime",
            "date", "@t", "t");

    private static final Set<String> STRING_START = Set.of("\"");
    private static final char SEP = '\u001f';

    private StructuredLines() {
    }

    /// Builds the processed form of a structured line.
    ///
    /// @param number     1-based line number
    /// @param line       the original line
    /// @param fields     its parsed fields
    /// @param normalized the line after rules were applied to the whole text
    /// @param ignore     which fields to leave out
    /// @param normalizer supplies the case and whitespace options
    public static ProcessedLine process(int number, String line, List<Field> fields, NormalizedText normalized,
                                        FieldFilter ignore, Normalizer normalizer) {
        List<Mask> masks = new ArrayList<>(normalized.masks());
        boolean[] ignored = new boolean[fields.size()];
        boolean any = false;
        for (int i = 0; i < fields.size(); i++) {
            if (ignore.ignores(fields.get(i).name())) {
                ignored[i] = true;
                any = true;
            }
        }
        if (any) {
            for (int i = 0; i < fields.size(); i++) {
                if (ignored[i]) {
                    Field f = fields.get(i);
                    int start = f.valueStart();
                    int end = f.valueEnd();
                    for (Mask m : masks) {
                        if (m.start() < end && start < m.end()) {
                            start = Math.min(start, m.start());
                            end = Math.max(end, m.end());
                        }
                    }
                    int s = start;
                    int e = end;
                    masks.removeIf(m -> m.start() < e && s < m.end());
                    masks.add(new Mask(s, e, IGNORED, IGNORED_FIELD_RULE));
                }
            }
            masks.sort(Comparator.comparingInt(Mask::start));
        }
        String normalizedText = any ? apply(line, masks, 0, line.length()) : normalized.normalized();

        List<StructuredValue> values = new ArrayList<>(fields.size());
        List<String> keyParts = new ArrayList<>(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            String value;
            if (ignored[i]) {
                value = IGNORED;
            } else {
                value = apply(line, masks, f.valueStart(), f.valueEnd());
                if (value.length() >= 2 && STRING_START.contains(value.substring(0, 1)) && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                keyParts.add(f.name() + "=" + normalizer.applyOptions(value));
            }
            values.add(new StructuredValue(f, value, ignored[i]));
        }
        keyParts.sort(null);
        StringBuilder key = new StringBuilder(line.length());
        for (String part : keyParts) {
            if (!key.isEmpty()) {
                key.append(SEP);
            }
            key.append(part);
        }
        return new ProcessedLine(number, line, normalizedText, masks, key.toString(), values);
    }

    /// Text of `line[from, to)` with every mask inside replaced.
    static String apply(String line, List<Mask> masks, int from, int to) {
        StringBuilder sb = new StringBuilder(to - from);
        int pos = from;
        for (Mask m : masks) {
            if (m.end() <= from || m.start() >= to) {
                continue;
            }
            if (m.start() > pos) {
                sb.append(line, pos, m.start());
            }
            if (m.start() >= from) {
                sb.append(m.replacement());
            }
            pos = Math.max(pos, Math.min(m.end(), to));
        }
        if (pos < to) {
            sb.append(line, pos, to);
        }
        return sb.toString();
    }

    /// Highlights the fields that differ between two structured lines: the value of a changed
    /// field, and the whole `key: value` of a field present on one side only.
    public static InlineHighlights highlights(ProcessedLine left, ProcessedLine right, Normalizer options) {
        List<Span> l = new ArrayList<>();
        List<Span> r = new ArrayList<>();
        for (FieldChange c : changes(left, right, options)) {
            if (c.left() != null && c.right() != null) {
                l.add(new Span(c.left().field().valueStart(), c.left().field().valueEnd()));
                r.add(new Span(c.right().field().valueStart(), c.right().field().valueEnd()));
            } else if (c.left() != null) {
                l.add(new Span(c.left().field().keyStart(), c.left().field().valueEnd()));
            } else {
                r.add(new Span(c.right().field().keyStart(), c.right().field().valueEnd()));
            }
        }
        l.sort(Comparator.comparingInt(Span::start));
        r.sort(Comparator.comparingInt(Span::start));
        return new InlineHighlights(l, r);
    }

    /// The fields that differ between two lines, in the order they appear (left fields first).
    /// Ignored fields and fields whose normalized values are equal are not reported.
    public static List<FieldChange> changes(ProcessedLine left, ProcessedLine right, Normalizer options) {
        if (!left.isStructured() || !right.isStructured()) {
            return List.of();
        }
        Map<String, List<StructuredValue>> rightByName = new HashMap<>();
        for (StructuredValue v : right.fields()) {
            if (!v.ignored()) {
                rightByName.computeIfAbsent(v.name(), _ -> new ArrayList<>()).add(v);
            }
        }
        List<FieldChange> out = new ArrayList<>();
        Map<String, Integer> used = new HashMap<>();
        for (StructuredValue lv : left.fields()) {
            if (lv.ignored()) {
                continue;
            }
            List<StructuredValue> candidates = rightByName.get(lv.name());
            int k = used.merge(lv.name(), 1, Integer::sum) - 1;
            StructuredValue rv = candidates != null && k < candidates.size() ? candidates.get(k) : null;
            if (rv == null) {
                out.add(new FieldChange(lv.name(), lv, null));
            } else if (!options.applyOptions(lv.normalized()).equals(options.applyOptions(rv.normalized()))) {
                out.add(new FieldChange(lv.name(), lv, rv));
            }
        }
        for (Map.Entry<String, List<StructuredValue>> e : rightByName.entrySet()) {
            int usedCount = used.getOrDefault(e.getKey(), 0);
            for (int k = usedCount; k < e.getValue().size(); k++) {
                out.add(new FieldChange(e.getKey(), null, e.getValue().get(k)));
            }
        }
        return out;
    }

    /// The message of a structured line (its `msg`/`message` field), or `null` if it has none.
    public static String message(ProcessedLine line, boolean normalized) {
        for (String name : MESSAGE_FIELDS) {
            StructuredValue v = line.field(name);
            if (v != null && !v.ignored()) {
                return normalized ? v.normalized() : v.value();
            }
        }
        return null;
    }

    /// The level field of a structured line, lower-cased, or `null`.
    public static String level(ProcessedLine line) {
        for (String name : LEVEL_FIELDS) {
            StructuredValue v = line.field(name);
            if (v != null) {
                return v.value().toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    /// The timestamp of a structured line from its time field, or [TimestampParser#NONE].
    public static long time(ProcessedLine line) {
        for (String name : TIME_FIELDS) {
            StructuredValue v = line.field(name);
            if (v != null) {
                long t = TimestampParser.parse(v.value().strip());
                if (t != TimestampParser.NONE) {
                    return t;
                }
            }
        }
        return TimestampParser.NONE;
    }

    /// One field that differs between two structured lines.
    ///
    /// @param name  field name
    /// @param left  the field in the baseline line, or `null` if it is missing there
    /// @param right the field in the candidate line, or `null` if it is missing there
    public record FieldChange(String name, StructuredValue left, StructuredValue right) {

        /// A compact description: `status: 200 → 502`, `+ retry: true`, `- cache: hit`.
        public String describe() {
            if (left == null) {
                return "+ " + name + ": " + right.value();
            }
            if (right == null) {
                return "- " + name + ": " + left.value();
            }
            return name + ": " + left.value() + " → " + right.value();
        }
    }
}
