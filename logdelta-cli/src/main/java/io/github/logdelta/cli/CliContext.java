// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Map;

/// The environment a command runs in. Abstracted so that commands can be tested without touching
/// the real console, environment variables or working directory.
///
/// @param out        standard output
/// @param err        standard error
/// @param in         standard input
/// @param env        environment variables
/// @param terminal   whether standard output is an interactive terminal
/// @param workingDir directory against which relative paths are resolved
/// @param unicode    whether standard output can encode box-drawing characters
public record CliContext(PrintStream out, PrintStream err, InputStream in, Map<String, String> env,
                         boolean terminal, Path workingDir, boolean unicode) {

    /// The context of the running JVM.
    public static CliContext system() {
        Charset charset = stdoutCharset();
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, charset);
        boolean unicode = charset.newEncoder().canEncode("\u2502\u2500\u2026\u2192");
        return new CliContext(out, err, System.in, System.getenv(), System.console() != null,
                Path.of("").toAbsolutePath(), unicode);
    }

    private static Charset stdoutCharset() {
        String name = System.getProperty("stdout.encoding", System.getProperty("sun.stdout.encoding"));
        try {
            return name == null ? Charset.defaultCharset() : Charset.forName(name);
        } catch (IllegalArgumentException _) {
            return Charset.defaultCharset();
        }
    }

    /// Resolves a user-supplied path against the working directory.
    public Path resolve(String path) {
        return workingDir.resolve(path).normalize();
    }

    /// Terminal width from `COLUMNS`, or `fallback`.
    public int terminalWidth(int fallback) {
        String cols = env.get("COLUMNS");
        if (cols != null) {
            try {
                return Integer.parseInt(cols.trim());
            } catch (NumberFormatException _) {
                // fall through
            }
        }
        return fallback;
    }

    /// Whether colour output should be used for `mode` (`auto`, `always` or
    /// `never`). `auto` honours [NO_COLOR](https://no-color.org) and
    /// only colours interactive terminals.
    public boolean useColor(String mode) {
        return switch (mode) {
            case "always" -> true;
            case "never" -> false;
            default -> terminal && !env.containsKey("NO_COLOR") && !"dumb".equals(env.get("TERM"));
        };
    }
}
