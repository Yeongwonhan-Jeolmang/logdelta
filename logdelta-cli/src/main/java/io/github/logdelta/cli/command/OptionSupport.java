// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.command;

import io.github.logdelta.cli.CliContext;
import io.github.logdelta.cli.args.ArgParser;
import io.github.logdelta.cli.args.OptionSpec;
import io.github.logdelta.cli.args.ParsedArgs;
import io.github.logdelta.cli.args.UsageException;
import io.github.logdelta.core.config.ProfileIO;
import io.github.logdelta.core.config.ProfileParseException;
import io.github.logdelta.core.diff.Algorithm;
import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.normalize.Rule;
import io.github.logdelta.core.normalize.RuleSet;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Options shared by every command that normalizes logs (`diff`, `normalize`,
/// `explain`, `rules`), and the logic that turns them into [DiffOptions].
///
/// Precedence, from lowest to highest: built-in defaults, the profile (`-p`), then
/// individual command-line flags.
final class OptionSupport {

    private OptionSupport() {
    }

    /// Adds the rule and normalization options to `parser`.
    static ArgParser addNormalizationOptions(ArgParser parser) {
        return parser
                .add(OptionSpec.value("profile", 'p', "FILE", "load rules and options from a profile (.ldprofile)"))
                .add(OptionSpec.repeated("mask", 'm', "REGEX[=>REPL]",
                        "add a rule; matches are replaced by REPL (default <MASK>)"))
                .add(OptionSpec.repeated("enable", '\0', "ID", "enable a rule by id, or 'all'"))
                .add(OptionSpec.repeated("disable", '\0', "ID", "disable a rule by id, or 'all'"))
                .add(OptionSpec.flag("no-builtins", '\0', "disable every built-in rule"))
                .add(OptionSpec.repeated("ignore", 'I', "REGEX", "leave out lines matching REGEX"))
                .add(OptionSpec.flag("ignore-case", 'i', "compare case-insensitively"))
                .add(OptionSpec.flag("ignore-whitespace", 'w', "ignore whitespace differences"));
    }

    /// Builds diff options from the parsed arguments.
    ///
    /// @throws UsageException for unknown rule ids, invalid patterns or unreadable profiles
    static DiffOptions buildOptions(ParsedArgs args, CliContext ctx) throws UsageException {
        return buildOptions(args, ctx, DiffOptions.defaults());
    }

    /// Builds diff options on top of `base` (for example a session's options). A profile
    /// given with `-p` replaces `base`.
    ///
    /// @throws UsageException for unknown rule ids, invalid patterns or unreadable profiles
    static DiffOptions buildOptions(ParsedArgs args, CliContext ctx, DiffOptions base) throws UsageException {
        if (args.has("profile")) {
            Path file = ctx.resolve(args.get("profile"));
            try {
                base = ProfileIO.read(file);
            } catch (IOException e) {
                throw new UsageException("Cannot read profile " + file + ": " + e.getMessage());
            } catch (ProfileParseException e) {
                throw new UsageException("Invalid profile " + file + ": " + e.getMessage());
            }
        }
        DiffOptions.Builder b = base.toBuilder();
        RuleSet rules = base.rules();
        try {
            if (args.has("no-builtins")) {
                for (Rule r : rules.rules()) {
                    if (r.builtin()) {
                        rules = rules.with(r.withEnabled(false));
                    }
                }
            }
            rules = toggle(rules, args.all("enable"), true);
            rules = toggle(rules, args.all("disable"), false);
            int n = 1;
            List<Rule> adHoc = new ArrayList<>();
            for (String spec : args.all("mask")) {
                adHoc.add(parseMask(spec, "mask-" + n++));
            }
            for (int i = adHoc.size() - 1; i >= 0; i--) {
                rules = rules.withAt(0, adHoc.get(i)); // ad-hoc rules run first, in the given order
            }
            List<String> ignore = new ArrayList<>(base.ignorePatterns());
            ignore.addAll(args.all("ignore"));
            b.rules(rules).ignorePatterns(ignore);
            if (args.has("ignore-case")) {
                b.ignoreCase(true);
            }
            if (args.has("ignore-whitespace")) {
                b.ignoreWhitespace(true);
            }
            if (args.has("algorithm")) {
                b.algorithm(Algorithm.parse(args.get("algorithm")));
            }
            if (args.has("minimal")) {
                b.minimal(true);
            }
            if (args.has("no-moves")) {
                b.detectMoves(false);
            }
            if (args.has("multiline")) {
                b.multilineEntries(true);
            }
            return b.build();
        } catch (IllegalArgumentException e) { // includes InvalidRuleException
            throw new UsageException(e.getMessage());
        }
    }

    private static RuleSet toggle(RuleSet rules, List<String> ids, boolean enabled) throws UsageException {
        RuleSet out = rules;
        for (String id : ids) {
            if (id.equals("all")) {
                out = out.withAllEnabled(enabled);
            } else if (out.find(id) == null) {
                throw new UsageException("Unknown rule '" + id + "'. Run 'logdelta rules' to list rule ids.");
            } else {
                out = out.withEnabled(id, enabled);
            }
        }
        return out;
    }

    /// Parses `REGEX` or `REGEX=>REPLACEMENT`.
    static Rule parseMask(String spec, String id) {
        int sep = spec.lastIndexOf("=>");
        String regex = sep >= 0 ? spec.substring(0, sep) : spec;
        String replacement = sep >= 0 ? spec.substring(sep + 2) : "<MASK>";
        return Rule.builder(id).name("Command-line mask " + id.substring(id.indexOf('-') + 1))
                .pattern(regex).replacement(replacement).build();
    }

    /// Parses `--encoding`, or returns `null` for auto-detection.
    static Charset charset(ParsedArgs args) throws UsageException {
        String name = args.get("encoding");
        if (name == null || name.equalsIgnoreCase("auto")) {
            return null;
        }
        try {
            return Charset.forName(name);
        } catch (UnsupportedCharsetException | java.nio.charset.IllegalCharsetNameException _) {
            throw new UsageException("Unknown encoding '" + name + "'");
        }
    }
}
