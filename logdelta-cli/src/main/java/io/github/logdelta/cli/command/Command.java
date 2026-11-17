// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.command;

import io.github.logdelta.cli.CliContext;
import io.github.logdelta.cli.args.ArgParser;
import io.github.logdelta.cli.args.ParsedArgs;

/// A sub-command such as `diff` or `rules`.
public interface Command {

    /// Name used on the command line.
    String name();

    /// One-line description for the command list.
    String summary();

    /// Usage line(s) without the program name, e.g. `"diff [options] BASELINE CANDIDATE"`.
    String usage();

    /// Longer help text printed after the option list; may be empty.
    default String details() {
        return "";
    }

    /// The parser declaring this command's options.
    ArgParser parser();

    /// Runs the command.
    ///
    /// @return the process exit code (see [io.github.logdelta.cli.ExitCode])
    /// @throws io.github.logdelta.cli.args.UsageException for invalid arguments
    /// @throws Exception      for I/O and other failures; reported by the caller
    int execute(ParsedArgs args, CliContext ctx) throws Exception;
}
