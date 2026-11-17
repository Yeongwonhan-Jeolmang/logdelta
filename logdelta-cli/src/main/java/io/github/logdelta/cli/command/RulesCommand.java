// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.command;

import io.github.logdelta.cli.CliContext;
import io.github.logdelta.cli.ExitCode;
import io.github.logdelta.cli.args.ArgParser;
import io.github.logdelta.cli.args.OptionSpec;
import io.github.logdelta.cli.args.ParsedArgs;
import io.github.logdelta.cli.render.Ansi;
import io.github.logdelta.core.config.ProfileIO;
import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.normalize.Rule;
import io.github.logdelta.core.normalize.RuleCategory;
import io.github.logdelta.core.util.Text;

import java.io.PrintStream;
import java.nio.file.Path;

/// `logdelta rules`: lists the effective rules and can export them as a profile.
public final class RulesCommand implements Command {

    @Override
    public String name() {
        return "rules";
    }

    @Override
    public String summary() {
        return "list normalization rules or export them as a profile";
    }

    @Override
    public String usage() {
        return "rules [options]";
    }

    @Override
    public String details() {
        return """
                Shows the rules in evaluation order after applying --profile, --enable, --disable and --mask.

                Example: start a team profile from the defaults plus one custom mask
                  logdelta rules --mask 'ORD-\\d+=>ORD-<ID>' --disable epoch --export team.ldprofile
                """;
    }

    @Override
    public ArgParser parser() {
        ArgParser p = new ArgParser();
        OptionSupport.addNormalizationOptions(p);
        return p.add(OptionSpec.flag("verbose", 'v', "also show patterns and descriptions"))
                .add(OptionSpec.value("export", '\0', "FILE", "write the effective options as a profile"))
                .add(OptionSpec.value("color", '\0', "WHEN", "auto (default), always or never"))
                .add(OptionSpec.flag("help", 'h', "show this help"));
    }

    @Override
    public int execute(ParsedArgs args, CliContext ctx) throws Exception {
        DiffOptions options = OptionSupport.buildOptions(args, ctx);
        if (args.has("export")) {
            Path file = ctx.resolve(args.get("export"));
            ProfileIO.write(options, file);
            ctx.err().println("Wrote profile to " + file);
            return ExitCode.OK;
        }
        Ansi ansi = Ansi.of(ctx.useColor(args.get("color", "auto")));
        boolean verbose = args.has("verbose");
        PrintStream out = ctx.out();
        RuleCategory current = null;
        for (Rule r : options.rules()) {
            if (r.category() != current) {
                current = r.category();
                out.println();
                out.println(ansi.bold(current.displayName()));
            }
            String state = r.enabled() ? ansi.green("on ") : ansi.dim("off");
            String id = String.format("%-18s", r.id());
            out.println("  " + state + "  " + (r.enabled() ? id : ansi.dim(id)) + "  "
                    + String.format("%-14s", r.replacement()) + "  " + ansi.dim(r.name()));
            if (verbose) {
                out.println("         " + ansi.cyan(r.regex()));
                if (!r.description().isEmpty()) {
                    out.println("         " + ansi.dim(Text.truncate(r.description(), 100)));
                }
            }
        }
        if (!options.ignorePatterns().isEmpty()) {
            out.println();
            out.println(ansi.bold("Ignored lines"));
            options.ignorePatterns().forEach(p -> out.println("  " + p));
        }
        out.println();
        return ExitCode.OK;
    }
}
