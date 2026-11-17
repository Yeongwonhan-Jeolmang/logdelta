// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli;

import io.github.logdelta.cli.args.ArgParser;
import io.github.logdelta.cli.args.OptionSpec;
import io.github.logdelta.cli.args.ParsedArgs;
import io.github.logdelta.cli.args.UsageException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArgParserTest {

    private static ArgParser parser() {
        return new ArgParser()
                .add(OptionSpec.value("format", 'f', "FMT", "format"))
                .add(OptionSpec.repeated("ignore", 'I', "REGEX", "ignore"))
                .add(OptionSpec.flag("ignore-case", 'i', "case"))
                .add(OptionSpec.flag("ignore-whitespace", 'w', "ws"));
    }

    @Test
    @DisplayName("long, short, attached and grouped forms")
    void forms() throws UsageException {
        ParsedArgs a = parser().parse(List.of("--format=json", "-Ifoo", "-I", "bar", "-iw", "a.log", "b.log"));
        assertEquals("json", a.get("format"));
        assertEquals(List.of("foo", "bar"), a.all("ignore"));
        assertTrue(a.has("ignore-case"));
        assertTrue(a.has("ignore-whitespace"));
        assertEquals(List.of("a.log", "b.log"), a.positionals());
    }

    @Test
    @DisplayName("double dash ends option parsing and '-' is positional")
    void terminator() throws UsageException {
        ParsedArgs a = parser().parse(List.of("-", "--", "-i"));
        assertEquals(List.of("-", "-i"), a.positionals());
        assertFalse(a.has("ignore-case"));
    }

    @Test
    @DisplayName("helpful errors for mistakes")
    void errors() {
        UsageException e = assertThrows(UsageException.class, () -> parser().parse(List.of("--fromat", "x")));
        assertTrue(e.getMessage().contains("did you mean --format"), e.getMessage());
        assertThrows(UsageException.class, () -> parser().parse(List.of("--format")));
        assertThrows(UsageException.class, () -> parser().parse(List.of("-x")));
        assertThrows(UsageException.class, () -> parser().parse(List.of("--ignore-case=yes")));
        assertThrows(UsageException.class, () -> parser().parse(List.of("-f", "a", "-f", "b")));
    }

    @Test
    @DisplayName("help lists every option")
    void help() {
        String help = parser().optionsHelp();
        assertTrue(help.contains("-f, --format FMT"));
        assertTrue(help.contains("(repeatable)"));
    }
}
