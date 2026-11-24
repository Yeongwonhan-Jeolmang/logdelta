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
import io.github.logdelta.core.structured.LogFormat;
import io.github.logdelta.core.timing.TimeWindow;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Options shared by every command that normalizes logs (`diff`, `normalize`,
/// `explain`, `rules`), and the logic that turns them into [DiffOptions].
///
/// Precedence, from lowest to highest: built-in defaults, the profile (`-p`, or a `.logdelta`
/// file found automatically), then individual command-line flags.
final class OptionSupport {

    /// Name of the profile picked up automatically from the working folder or a parent of it.
    static final String AUTO_PROFILE = ".logdelta";

    private OptionSupport() {
    }

    /// Profile file inside a `.logdelta` folder (for repositories that also keep baselines there).
    static final String AUTO_PROFILE_IN_FOLDER = "profile.ldprofile";

    /// Finds the automatic profile: a `.logdelta` file, or `.logdelta/profile.ldprofile`, in
    /// `dir` or the nearest parent folder, without leaving the enclosing Git repository (a folder
    /// containing `.git` is the last one searched).
    static Path findAutoProfile(Path dir) {
        return findAutoProfile(dir, homeDirectory());
    }

    /// Searches `dir` and its parents, stopping at a repository root (`.git`) or at `home`.
    /// Folders anyone may write to (such as `/tmp`) are skipped, so another user cannot slip
    /// a profile into a comparison.
    static Path findAutoProfile(Path dir, Path home) {
        Path d = dir.toAbsolutePath().normalize();
        while (d != null) {
            if (!writableByOthers(d)) {
                Path candidate = d.resolve(AUTO_PROFILE);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
                Path inFolder = candidate.resolve(AUTO_PROFILE_IN_FOLDER);
                if (Files.isRegularFile(inFolder)) {
                    return inFolder;
                }
            }
            if (Files.exists(d.resolve(".git")) || d.equals(home)) {
                return null;
            }
            d = d.getParent();
        }
        return null;
    }

    private static Path homeDirectory() {
        String home = System.getProperty("user.home");
        return home == null || home.isBlank() ? null : Path.of(home).toAbsolutePath().normalize();
    }

    private static boolean writableByOthers(Path dir) {
        try {
            return Files.getPosixFilePermissions(dir).contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return false;
        }
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
                .add(OptionSpec.flag("ignore-whitespace", 'w', "ignore whitespace differences"))
                .add(OptionSpec.value("log-format", '\0', "FMT", "auto (default), plain, json or logfmt"))
                .add(OptionSpec.repeated("ignore-field", '\0', "NAME",
                        "structured logs: leave out a field (glob; e.g. requestId, *_ms)"))
                .add(OptionSpec.repeated("compare-field", '\0', "NAME",
                        "structured logs: compare a field that is ignored by default (e.g. ts)"))
                .add(OptionSpec.value("preset", '\0', "ID", "built-in format profile: auto (default), none or an id"))
                .add(OptionSpec.value("since", '\0', "TIME", "only compare from TIME: +5m (into the run), 08:49, "
                        + "2026-11-12T08:49"))
                .add(OptionSpec.value("until", '\0', "TIME", "only compare up to TIME (same forms as --since)"))
                .add(OptionSpec.flag("no-redact", '\0', "do not remove secrets and e-mail addresses from reports"))
                .add(OptionSpec.repeated("redact", '\0', "REGEX", "also remove matches of REGEX from reports"))
                .add(OptionSpec.flag("no-profile", '\0', "do not load a .logdelta profile automatically"));
    }

    /// Builds diff options from the parsed arguments.
    ///
    /// @throws UsageException for unknown rule ids, invalid patterns or unreadable profiles
    static DiffOptions buildOptions(ParsedArgs args, CliContext ctx) throws UsageException {
        return buildOptions(args, ctx, null);
    }

    /// Builds diff options on top of `base` (for example a session's options). A profile
    /// given with `-p` replaces `base`. When `base` is `null` and no profile is given, a
    /// `.logdelta` file in the working folder (or a parent, up to the Git root) is used.
    ///
    /// @throws UsageException for unknown rule ids, invalid patterns or unreadable profiles
    static DiffOptions buildOptions(ParsedArgs args, CliContext ctx, DiffOptions base) throws UsageException {
        Path profile = null;
        if (args.has("profile")) {
            profile = ctx.resolve(args.get("profile"));
        } else if (base == null && !args.has("no-profile") && !"0".equals(ctx.env().get("LOGDELTA_AUTO_PROFILE"))) {
            profile = findAutoProfile(ctx.resolve("."));
            if (profile != null && !args.has("quiet")) {
                ctx.err().println("logdelta: using profile " + profile);
            }
        }
        if (base == null) {
            base = DiffOptions.defaults();
        }
        if (profile != null) {
            Path file = profile;
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
            if (args.has("log-format")) {
                b.format(LogFormat.parse(args.get("log-format")));
            }
            List<String> fields = new ArrayList<>(base.ignoreFields());
            for (String f : args.all("ignore-field")) {
                if (!fields.contains(f)) {
                    fields.add(f);
                }
            }
            List<String> compare = args.all("compare-field");
            fields.removeIf(f -> compare.stream().anyMatch(f::equalsIgnoreCase));
            b.ignoreFields(fields);
            if (args.has("preset")) {
                b.preset(args.get("preset"));
            }
            if (args.has("since") || args.has("until")) {
                // A bound given on the command line replaces the profile's; the other one is kept.
                TimeWindow fromProfile = base.timeWindow();
                String since = args.has("since") ? args.get("since")
                        : fromProfile == null || fromProfile.since() == null ? null : fromProfile.since().text();
                String until = args.has("until") ? args.get("until")
                        : fromProfile == null || fromProfile.until() == null ? null : fromProfile.until().text();
                TimeWindow w = TimeWindow.parse(since, until);
                b.timeWindow(w);
            }
            if (args.has("no-redact")) {
                b.redactSecrets(false);
            }
            List<String> redact = new ArrayList<>(base.redactPatterns());
            redact.addAll(args.all("redact"));
            b.redactPatterns(redact);
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
