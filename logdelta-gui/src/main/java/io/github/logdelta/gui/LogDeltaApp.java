// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui;

import io.github.logdelta.core.Version;
import io.github.logdelta.gui.settings.AppSettings;

import javax.swing.SwingUtilities;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.util.List;

/// Desktop application entry point.
///
/// ```
/// java -jar logdelta.jar gui [BASELINE... CANDIDATE | BASELINE_DIR CANDIDATE_DIR | SESSION]
/// ```
///
/// Inputs may be files or `http(s)` URLs; with more than two, all but the last are extra
/// baselines.
public final class LogDeltaApp {

    private LogDeltaApp() {
    }

    /// Starts the GUI and opens whatever the arguments name. Without arguments, first-time users
    /// get a short tour.
    ///
    /// @param args optional logs, folders or a session file
    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(Version.NAME + ": no display available. Use the command line instead, e.g.\n"
                    + "  logdelta baseline.log candidate.log");
            System.exit(2);
        }
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", Version.NAME);
        SwingUtilities.invokeLater(() -> {
            AppSettings settings = AppSettings.load();
            settings.theme().install();
            MainFrame frame = new MainFrame(settings);
            frame.setVisible(true);
            if (args.length > 0) {
                frame.openArguments(List.of(args));
            } else {
                frame.maybeShowTour();
            }
            // macOS delivers files opened from Finder (e.g. a double-clicked .ldsession) here.
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_FILE)) {
                Desktop.getDesktop().setOpenFileHandler(e -> SwingUtilities.invokeLater(() ->
                        frame.openArguments(e.getFiles().stream().map(java.io.File::getPath).toList())));
            }
        });
    }
}
