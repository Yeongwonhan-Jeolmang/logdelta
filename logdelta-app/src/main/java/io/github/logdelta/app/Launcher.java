// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.app;

import io.github.logdelta.cli.CliContext;
import io.github.logdelta.cli.ExitCode;
import io.github.logdelta.cli.LogDeltaCli;
import io.github.logdelta.gui.LogDeltaApp;

import java.awt.GraphicsEnvironment;
import java.util.Arrays;

/// Entry point of the all-in-one jar.
///
/// - `java -jar logdelta.jar` — opens the desktop application (also what happens
///   when the jar is double-clicked);
/// - `java -jar logdelta.jar gui [BASELINE CANDIDATE]` — opens the desktop
///   application, optionally comparing two files right away;
/// - `java -jar logdelta.jar cli [ARGS]` — always the command line, even without further
///   arguments.
///
/// The native installers set the system property `logdelta.launcher` to `gui` for the
/// desktop launcher (so files opened from the file manager reach the GUI) and to `cli` for the
/// `logdelta-cli` launcher.
/// - anything else is handed to the command-line interface, e.g.
///   `java -jar logdelta.jar good.log bad.log`.
public final class Launcher {

    private Launcher() {
    }

    /// Dispatches to the GUI or the CLI.
    public static void main(String[] args) {
        Mode mode = mode(args, GraphicsEnvironment.isHeadless(), System.getProperty("logdelta.launcher"));
        switch (mode) {
            case GUI -> LogDeltaApp.main(args.length > 0 && args[0].equals("gui")
                    ? Arrays.copyOfRange(args, 1, args.length) : args);
            case CLI -> System.exit(new LogDeltaCli().run(cliArgs(args), CliContext.system()));
            case HELP -> {
                // No display for the GUI: explain the command line instead of failing silently.
                new LogDeltaCli().run(new String[] {"--help"}, CliContext.system());
                System.exit(ExitCode.ERROR);
            }
            default -> throw new IllegalStateException(mode.toString());
        }
    }

    /// What to start for the given arguments.
    enum Mode {
        GUI, CLI, HELP
    }

    /// Decides between GUI and CLI; package-private for tests.
    ///
    /// @param launcher value of the `logdelta.launcher` property, or `null`
    static Mode mode(String[] args, boolean headless, String launcher) {
        if ("cli".equals(launcher)) {
            return Mode.CLI;
        }
        if ("gui".equals(launcher) && !(args.length > 0 && args[0].equals("cli"))) {
            return headless ? Mode.HELP : Mode.GUI;
        }
        if (args.length > 0 && args[0].equals("cli")) {
            return Mode.CLI;
        }
        if (args.length == 0) {
            return headless ? Mode.HELP : Mode.GUI;
        }
        if (args[0].equals("gui")) {
            return Mode.GUI;
        }
        return Mode.CLI;
    }

    /// The arguments for the CLI: a leading `cli` is dropped, and no arguments at all become
    /// `--help`.
    static String[] cliArgs(String[] args) {
        String[] rest = args.length > 0 && args[0].equals("cli") ? Arrays.copyOfRange(args, 1, args.length) : args;
        return rest.length > 0 ? rest : new String[] {"--help"};
    }
}
