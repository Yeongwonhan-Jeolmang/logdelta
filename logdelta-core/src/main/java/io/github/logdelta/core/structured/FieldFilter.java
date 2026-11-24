// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.structured;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/// Decides which fields of structured lines are ignored.
///
/// - A name with a dot (`http.request.id`) must match the field's full dotted name.
/// - A name without a dot (`traceId`) matches the full name of a top-level field **or** the last
///   segment of a nested one, so `traceId` also ignores `span.traceId`.
/// - `*` matches any run of characters: `*_id`, `trace.*`.
///
/// Matching ignores case, as loggers disagree about `traceId` versus `TraceID`.
public final class FieldFilter {

    private static final FieldFilter NONE = new FieldFilter(List.of());

    private final List<String> names;
    /// Patterns matched against the full name.
    private final Pattern full;
    /// Patterns without a dot, also matched against the last segment.
    private final Pattern simple;

    private FieldFilter(List<String> names) {
        this.names = List.copyOf(names);
        List<String> all = new ArrayList<>();
        List<String> undotted = new ArrayList<>();
        for (String n : names) {
            String name = n.strip();
            if (name.isEmpty()) {
                continue;
            }
            String regex = glob(name);
            all.add(regex);
            if (name.indexOf('.') < 0) {
                undotted.add(regex);
            }
        }
        this.full = compile(all);
        this.simple = compile(undotted);
    }

    private static String glob(String name) {
        String[] pieces = name.split("\\*", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pieces.length; i++) {
            if (i > 0) {
                sb.append(".*");
            }
            if (!pieces[i].isEmpty()) {
                sb.append(Pattern.quote(pieces[i]));
            }
        }
        return sb.toString();
    }

    private static Pattern compile(List<String> regexes) {
        return regexes.isEmpty() ? null
                : Pattern.compile("(?:" + String.join(")|(?:", regexes) + ")", Pattern.CASE_INSENSITIVE);
    }

    /// A filter for the given names or globs.
    public static FieldFilter of(List<String> names) {
        return names.isEmpty() ? NONE : new FieldFilter(names);
    }

    /// The names this filter was created from.
    public List<String> names() {
        return names;
    }

    /// Whether a field with the given (dotted) name is ignored.
    public boolean ignores(String fieldName) {
        if (full != null && full.matcher(fieldName).matches()) {
            return true;
        }
        int dot = fieldName.lastIndexOf('.');
        return simple != null && dot >= 0 && simple.matcher(fieldName.substring(dot + 1)).matches();
    }
}
