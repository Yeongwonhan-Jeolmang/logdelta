// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.model;

import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.ProcessedLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/// Finds text in both sides of a comparison.
///
/// Searches the text that is currently displayed: original lines, or normalized lines when the
/// view shows normalized text.
public final class SearchModel {

    /// One match.
    ///
    /// @param row   diff row index
    /// @param left  whether the match is on the left side
    /// @param start start offset within the displayed text
    /// @param end   end offset within the displayed text
    public record Match(int row, boolean left, int start, int end) {
    }

    private final List<Match> matches;
    private final Map<Integer, List<Match>> byRow;
    private final String error;

    private SearchModel(List<Match> matches, String error) {
        this.matches = List.copyOf(matches);
        this.error = error;
        Map<Integer, List<Match>> map = new HashMap<>();
        for (Match m : matches) {
            map.computeIfAbsent(m.row(), _ -> new ArrayList<>()).add(m);
        }
        this.byRow = map;
    }

    /// An empty search.
    public static SearchModel empty() {
        return new SearchModel(List.of(), null);
    }

    /// Runs a search.
    ///
    /// @param result     comparison to search
    /// @param query      text or regular expression; blank yields no matches
    /// @param regex      interpret `query` as a regular expression
    /// @param matchCase  case-sensitive matching
    /// @param normalized search normalized text instead of original text
    public static SearchModel search(DiffResult result, String query, boolean regex, boolean matchCase,
                                     boolean normalized) {
        if (result == null || query == null || query.isEmpty()) {
            return empty();
        }
        Pattern pattern;
        try {
            int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            pattern = Pattern.compile(regex ? query : Pattern.quote(query), flags);
        } catch (PatternSyntaxException e) {
            return new SearchModel(List.of(), e.getDescription());
        }
        List<Match> out = new ArrayList<>();
        List<DiffRow> rows = result.rows();
        for (int i = 0; i < rows.size(); i++) {
            DiffRow row = rows.get(i);
            find(pattern, result.left(row), normalized, i, true, out);
            find(pattern, result.right(row), normalized, i, false, out);
        }
        return new SearchModel(out, null);
    }

    private static void find(Pattern p, ProcessedLine line, boolean normalized, int row, boolean left,
                             List<Match> out) {
        if (line == null) {
            return;
        }
        Matcher m = p.matcher(normalized ? line.normalized() : line.text());
        while (m.find()) {
            if (m.end() > m.start()) {
                out.add(new Match(row, left, m.start(), m.end()));
            }
        }
    }

    /// All matches in row order, left side before right side.
    public List<Match> matches() {
        return matches;
    }

    /// Matches in one row.
    public List<Match> matchesInRow(int row) {
        return byRow.getOrDefault(row, Collections.emptyList());
    }

    /// Number of matches.
    public int size() {
        return matches.size();
    }

    /// A message describing an invalid regular expression, or `null`.
    public String error() {
        return error;
    }
}
