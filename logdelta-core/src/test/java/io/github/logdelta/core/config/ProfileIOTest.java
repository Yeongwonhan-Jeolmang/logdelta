// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.config;

import io.github.logdelta.core.diff.Algorithm;
import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.normalize.Rule;
import io.github.logdelta.core.normalize.RuleCategory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileIOTest {

    @Test
    @DisplayName("parses options, ignore patterns and rules")
    void parse() throws ProfileParseException {
        DiffOptions o = ProfileIO.parse("""
                # comment
                [options]
                algorithm = Patience
                ignore-case = yes
                ignore-whitespace = on
                detect-moves = false

                [ignore]
                pattern = ^DEBUG
                pattern = health # not a comment: part of the regex

                [rule uuid]
                enabled = false

                [rule order-id]
                name = Order id
                category = identifier
                pattern = ORD-\\d+
                replacement = "<ORDER> "
                """);
        assertEquals(Algorithm.PATIENCE, o.algorithm());
        assertTrue(o.ignoreCase());
        assertTrue(o.ignoreWhitespace());
        assertFalse(o.detectMoves());
        assertEquals(List.of("^DEBUG", "health # not a comment: part of the regex"), o.ignorePatterns());
        assertFalse(o.rules().find("uuid").enabled());
        Rule custom = o.rules().rules().get(0);
        assertEquals("order-id", custom.id());
        assertEquals(RuleCategory.IDENTIFIER, custom.category());
        assertEquals("<ORDER> ", custom.replacement());
    }

    @Test
    @DisplayName("format and parse round-trip, including order and quoting")
    void roundTrip() throws ProfileParseException {
        DiffOptions original = DiffOptions.builder()
                .algorithm(Algorithm.PATIENCE)
                .ignoreCase(true)
                .addIgnorePattern(" leading space")
                .rules(DiffOptions.defaults().rules()
                        .withEnabled("number", true)
                        .withEnabled("ipv4", false)
                        .withAt(3, Rule.builder("quote").pattern("\"[^\"]*\"").replacement("<STR>")
                                .description("quoted strings").caseInsensitive(true).build())
                        .move(0, 5))
                .build();
        String text = ProfileIO.format(original);
        assertTrue(text.contains("order = "), text);
        assertEquals(original, ProfileIO.parse(text));
    }

    @Test
    @DisplayName("builtins = off disables built-in rules unless re-enabled")
    void builtinsOff() throws ProfileParseException {
        DiffOptions o = ProfileIO.parse("[options]\nbuiltins = off\n[rule uuid]\nenabled = true\n");
        assertEquals(List.of("uuid"), o.rules().enabledRules().stream().map(Rule::id).toList());
    }

    @Test
    @DisplayName("errors carry line numbers")
    void errors() {
        ProfileParseException e = assertThrows(ProfileParseException.class,
                () -> ProfileIO.parse("[options]\n\nalgorithm = quantum\n"));
        assertEquals(3, e.line());
        assertEquals(1, assertThrows(ProfileParseException.class, () -> ProfileIO.parse("[nope]")).line());
        assertEquals(2, assertThrows(ProfileParseException.class,
                () -> ProfileIO.parse("[rule x]\npattern = (\n")).line());
        assertEquals(1, assertThrows(ProfileParseException.class, () -> ProfileIO.parse("key = value")).line());
        assertThrows(ProfileParseException.class, () -> ProfileIO.parse("[rule uuid]\nname = x\n"));
        assertThrows(ProfileParseException.class, () -> ProfileIO.parse("[rule new]\nenabled = true\n"));
    }

    @Test
    @DisplayName("quoting is reversible")
    void quoting() throws ProfileParseException {
        for (String s : List.of("plain", " lead", "trail ", "\"q\"", "back\\slash ", "")) {
            assertEquals(s, ProfileIO.unquote(ProfileIO.quote(s), 1));
        }
    }
}
