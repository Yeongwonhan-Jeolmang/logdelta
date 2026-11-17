// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli;

import io.github.logdelta.cli.args.ParsedArgs;
import io.github.logdelta.cli.args.UsageException;
import io.github.logdelta.cli.command.CheckCommand;
import io.github.logdelta.cli.command.Command;
import io.github.logdelta.cli.command.DiffCommand;
import io.github.logdelta.cli.command.ExplainCommand;
import io.github.logdelta.cli.command.NormalizeCommand;
import io.github.logdelta.cli.command.RulesCommand;
import io.github.logdelta.core.Version;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Command-line entry point.
///
/// ```
/// logdelta [diff] [options] BASELINE... CANDIDATE
/// logdelta check [options] --baseline FILE CANDIDATE
/// logdelta normalize|explain|rules [options] ...
/// logdelta help [COMMAND]
/// ```
///
/// When the first argument is not a known command, `diff` is assumed.
public final class LogDeltaCli {

    private final Map<String, Command> commands = new LinkedHashMap<>();

    /// Creates the CLI with all built-in commands.
    public LogDeltaCli() {
        for (Command c : List.of(new DiffCommand(), new CheckCommand(), new NormalizeCommand(), new ExplainCommand(),
                new RulesCommand())) {
            commands.put(c.name(), c);
        }
    }

    /// Runs the CLI and exits the JVM with the resulting status.
    public static void main(String[] args) {
        System.exit(new LogDeltaCli().run(args, CliContext.system()));
    }

    /// Runs the CLI without exiting.
    ///
    /// @return the exit status (see [ExitCode])
    public int run(String[] argv, CliContext ctx) {
        List<String> args = Arrays.asList(argv);
        if (args.isEmpty()) {
            printUsage(ctx.err());
            return ExitCode.ERROR;
        }
        String first = args.get(0);
        switch (first) {
            case "--version", "-V", "version" -> {
                ctx.out().println(Version.displayName());
                return ExitCode.OK;
            }
            case "--help", "-h", "help" -> {
                if (args.size() > 1 && commands.containsKey(args.get(1))) {
                    printHelp(commands.get(args.get(1)), ctx.out());
                } else {
                    printUsage(ctx.out());
                }
                return ExitCode.OK;
            }
            default -> {
                // fall through to command dispatch
            }
        }
        Command command = commands.get(first);
        List<String> rest = command == null ? args : args.subList(1, args.size());
        if (command == null) {
            command = commands.get("diff");
        }
        try {
            ParsedArgs parsed = command.parser().parse(rest);
            if (parsed.has("help")) {
                printHelp(command, ctx.out());
                return ExitCode.OK;
            }
            return command.execute(parsed, ctx);
        } catch (UsageException e) {
            ctx.err().println("logdelta: " + e.getMessage());
            ctx.err().println("Try 'logdelta help " + command.name() + "' for more information.");
            return ExitCode.ERROR;
        } catch (IOException | UncheckedIOException e) {
            ctx.err().println("logdelta: I/O error: " + e.getMessage());
            return ExitCode.ERROR;
        } catch (OutOfMemoryError e) {
            ctx.err().println("logdelta: out of memory; increase the heap, e.g. JAVA_OPTS=-Xmx4g");
            return ExitCode.ERROR;
        } catch (Exception e) {
            ctx.err().println("logdelta: unexpected error: " + e);
            return ExitCode.ERROR;
        }
    }

    private void printUsage(PrintStream out) {
        out.println(Version.displayName() + " - compare log files while ignoring what always changes");
        out.println();
        out.println("Usage:");
        for (Command c : commands.values()) {
            out.println("  logdelta " + c.usage());
        }
        out.println("  logdelta gui [BASELINE CANDIDATE]      (desktop application, from the full distribution)");
        out.println();
        out.println("Commands:");
        for (Command c : commands.values()) {
            out.printf("  %-11s%s%n", c.name(), c.summary());
        }
        out.println();
        out.println("Run 'logdelta help COMMAND' for the options of a command.");
    }

    private static void printHelp(Command c, PrintStream out) {
        out.println("Usage: logdelta " + c.usage());
        out.println();
        out.println(c.summary().substring(0, 1).toUpperCase() + c.summary().substring(1) + ".");
        out.println();
        out.println("Options:");
        out.print(c.parser().optionsHelp());
        if (!c.details().isEmpty()) {
            out.println();
            out.print(c.details());
        }
    }
}
