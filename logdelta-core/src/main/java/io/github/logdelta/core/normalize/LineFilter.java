// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.normalize;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/// Decides which lines take part in a comparison.
///
/// A line is _ignored_ when it matches any ignore pattern (searched anywhere in the
/// original line). Ignored lines are removed before diffing, so noisy lines such as health
/// checks or heartbeats never show up as additions or removals.
public final class LineFilter {

    private static final LineFilter NONE = new LineFilter(List.of());

    private final List<Pattern> ignore;

    private LineFilter(List<Pattern> ignore) {
        this.ignore = List.copyOf(ignore);
    }

    /// A filter that keeps every line.
    public static LineFilter none() {
        return NONE;
    }

    /// Compiles a filter from regular expressions.
    ///
    /// @throws InvalidRuleException if a pattern does not compile
    public static LineFilter of(List<String> ignorePatterns) {
        if (ignorePatterns.isEmpty()) {
            return NONE;
        }
        List<Pattern> compiled = new ArrayList<>(ignorePatterns.size());
        for (String p : ignorePatterns) {
            try {
                compiled.add(Pattern.compile(p));
            } catch (PatternSyntaxException e) {
                throw new InvalidRuleException("Invalid ignore pattern '" + p + "': " + e.getDescription(), e);
            }
        }
        return new LineFilter(compiled);
    }

    /// Whether `line` should be left out of the comparison.
    public boolean isIgnored(String line) {
        for (Pattern p : ignore) {
            if (p.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    /// Whether the filter has any patterns at all.
    public boolean isEmpty() {
        return ignore.isEmpty();
    }
}
