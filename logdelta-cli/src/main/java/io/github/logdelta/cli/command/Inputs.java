// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.command;

import io.github.logdelta.cli.CliContext;
import io.github.logdelta.cli.args.ArgParser;
import io.github.logdelta.cli.args.OptionSpec;
import io.github.logdelta.cli.args.ParsedArgs;
import io.github.logdelta.cli.args.UsageException;
import io.github.logdelta.core.folder.FolderOptions;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.io.LogReader;
import io.github.logdelta.core.io.LogSources;
import io.github.logdelta.core.util.CancellationToken;
import io.github.logdelta.core.util.ProgressListener;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;

/// Loading of log inputs for the commands that compare logs: local files, standard input
/// (`-`) and `http(s)` URLs.
final class Inputs {

    private Inputs() {
    }

    /// Adds `--header` and `--timeout`, which apply to URL inputs.
    static ArgParser addSourceOptions(ArgParser parser) {
        return parser
                .add(OptionSpec.repeated("header", 'H', "'NAME: VALUE'", "HTTP header for URL inputs, e.g. Authorization"))
                .add(OptionSpec.value("timeout", '\0', "SECONDS", "timeout for URL inputs (default 30)"));
    }

    /// Builds the download settings from `--header` and `--timeout`.
    static LogSources.RemoteOptions remoteOptions(ParsedArgs args) throws UsageException {
        LogSources.RemoteOptions o = LogSources.RemoteOptions.DEFAULT;
        for (String h : args.all("header")) {
            try {
                String[] nv = LogSources.parseHeader(h);
                o = o.withHeader(nv[0], nv[1]);
            } catch (IllegalArgumentException e) {
                throw new UsageException(e.getMessage());
            }
        }
        if (args.has("timeout")) {
            int seconds = args.getInt("timeout", 30);
            if (seconds == 0) {
                throw new UsageException("--timeout must be at least 1 second");
            }
            o = new LogSources.RemoteOptions(o.headers(), Duration.ofSeconds(seconds), o.maxBytes());
        }
        return o;
    }

    /// Adds the options that control folder comparisons.
    static ArgParser addFolderOptions(ArgParser parser) {
        return parser
                .add(OptionSpec.repeated("include", '\0', "GLOB", "folders: only compare matching files, e.g. '*.log'"))
                .add(OptionSpec.repeated("exclude", '\0', "GLOB", "folders: skip matching files"))
                .add(OptionSpec.flag("no-recursive", '\0', "folders: do not descend into subfolders"))
                .add(OptionSpec.flag("no-rotated", '\0', "folders: only pair files with identical names"));
    }

    /// Builds folder settings from the folder options.
    static FolderOptions folderOptions(ParsedArgs args) {
        return FolderOptions.DEFAULT.withInclude(args.all("include")).withExclude(args.all("exclude"))
                .withRecursive(!args.has("no-recursive")).withMatchRotated(!args.has("no-rotated"));
    }

    /// Whether `spec` names a local folder.
    static boolean isDirectory(CliContext ctx, String spec) {
        return !spec.equals("-") && !LogSources.isUrl(spec) && Files.isDirectory(ctx.resolve(spec));
    }

    /// Loads one input.
    ///
    /// @throws UsageException for missing files, folders and malformed URLs
    static LogFile load(CliContext ctx, String spec, Charset charset, LogSources.RemoteOptions remote,
                        ProgressListener progress) throws IOException, UsageException {
        if (spec.equals("-")) {
            return LogReader.read("<stdin>", ctx.in(), charset);
        }
        if (LogSources.isUrl(spec)) {
            return LogSources.fetch(LogSources.toUri(spec), charset, remote, CancellationToken.NEVER);
        }
        Path path = ctx.resolve(spec);
        if (Files.isDirectory(path)) {
            throw new UsageException(spec + " is a folder; compare two folders, or give a file");
        }
        try {
            return LogReader.read(path, charset, progress, CancellationToken.NEVER);
        } catch (NoSuchFileException _) {
            throw new UsageException("File not found: " + spec);
        }
    }
}
