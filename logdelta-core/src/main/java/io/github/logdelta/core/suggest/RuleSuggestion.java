// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.suggest;

import io.github.logdelta.core.normalize.Rule;

/// A rule that would hide differences found in a comparison.
///
/// @param rule         the proposed rule (disabled rules are never proposed)
/// @param summary      what the affected lines have in common, e.g. "differ only in the value of `session=`"
/// @param resolves     number of modified lines that become identical with this rule
/// @param exampleRow   a diff row the rule resolves
/// @param exampleLeft  the baseline text of that row
/// @param exampleRight the candidate text of that row
public record RuleSuggestion(Rule rule, String summary, int resolves, int exampleRow, String exampleLeft,
                             String exampleRight) {
}
