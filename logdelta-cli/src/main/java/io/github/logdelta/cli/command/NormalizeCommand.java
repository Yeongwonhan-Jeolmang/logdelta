// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.command;

import io.github.logdelta.cli.CliContext;
import io.github.logdelta.cli.ExitCode;
import io.github.logdelta.cli.args.ArgParser;
import io.github.logdelta.cli.args.OptionSpec;
import io.github.logdelta.cli.args.ParsedArgs;
import io.github.logdelta.cli.args.UsageException;
import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.io.LogReader;
import io.github.logdelta.core.normalize.LineFilter;
import io.github.logdelta.core.normalize.Normalizer;

import java.io.PrintStream;
import java.nio.file.NoSuchFileException;

/// `logdelta normalize`: prints a log the way LogDelta sees it. Useful for checking rules
/// and for feeding normalized logs into other tools (`sort | uniq -c`, `grep`, ...).
public final class NormalizeCommand implements Command {

    @Override
    public String name() {
        return "normalize";
    }

    @Override
    public String summary() {
        return "print a log with all rules applied";
    }

    @Override
    public String usage() {
        return "normalize [options] FILE";
    }

    @Override
    public String details() {
        return """
                Ignored lines are omitted unless --keep-ignored is given. Use '-' to read standard input.

                Example:
                  logdelta normalize app.log | sort | uniq -c | sort -rn | head
                """;
    }

    @Override
    public ArgParser parser() {
        ArgParser p = new ArgParser();
        OptionSupport.addNormalizationOptions(p);
        return p.add(OptionSpec.flag("line-numbers", 'n', "prefix each line with its original line number"))
                .add(OptionSpec.flag("keep-ignored", '\0', "also print lines matched by --ignore"))
                .add(OptionSpec.value("encoding", 'e', "CHARSET", "input encoding (default: auto-detect)"))
                .add(OptionSpec.flag("help", 'h', "show this help"));
    }

    @Override
    public int execute(ParsedArgs args, CliContext ctx) throws Exception {
        if (args.positionals().size() != 1) {
            throw new UsageException("Expected exactly one FILE");
        }
        DiffOptions options = OptionSupport.buildOptions(args, ctx);
        Normalizer normalizer = options.normalizer();
        LineFilter filter = options.lineFilter();
        String name = args.positionals().get(0);
        LogFile file;
        try {
            file = name.equals("-") ? LogReader.read("<stdin>", ctx.in(), OptionSupport.charset(args))
                    : LogReader.read(ctx.resolve(name), OptionSupport.charset(args),
                    (_, _) -> { }, io.github.logdelta.core.util.CancellationToken.NEVER);
        } catch (NoSuchFileException _) {
            throw new UsageException("File not found: " + name);
        }
        PrintStream out = ctx.out();
        boolean numbers = args.has("line-numbers");
        boolean keep = args.has("keep-ignored");
        int i = 0;
        for (String line : file.lines()) {
            i++;
            if (!keep && filter.isIgnored(line)) {
                continue;
            }
            if (numbers) {
                out.print(i);
                out.print('\t');
            }
            out.println(normalizer.normalize(line).normalized());
        }
        out.flush();
        return ExitCode.OK;
    }
}
