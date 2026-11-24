// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui;

import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.report.ReportFormat;
import io.github.logdelta.core.report.ReportOptions;
import io.github.logdelta.core.report.Reports;
import io.github.logdelta.gui.command.MenuCommands;
import io.github.logdelta.gui.i18n.I18n;
import io.github.logdelta.gui.settings.AppSettings;
import io.github.logdelta.gui.tab.ComparisonTab;
import io.github.logdelta.gui.theme.Theme;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.prefs.Preferences;

/// `logdelta gui --smoke-test`: starts the real application with throw-away settings, opens the
/// bundled example and checks that the main features work end to end. Used by CI under a
/// virtual X display (`xvfb-run`). Exits with 0 on success and 1 on any failure or after
/// [#TIMEOUT_MS].
final class SmokeTest {

    static final int TIMEOUT_MS = 120_000;

    private SmokeTest() {
    }

    static void run() {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("smoke test: no display (run it under xvfb-run)");
            System.exit(1);
        }
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> fail("uncaught exception in " + t.getName() + ": " + e, e));
        Timer timeout = new Timer(TIMEOUT_MS, _ -> fail("timed out after " + TIMEOUT_MS / 1000 + " s", null));
        timeout.setRepeats(false);
        timeout.start();
        SwingUtilities.invokeLater(() -> {
            try {
                Path config = Files.createTempDirectory("logdelta-smoke");
                Preferences prefs = Preferences.userRoot().node("io/github/logdelta-smoke-test");
                prefs.clear();
                AppSettings settings = new AppSettings(prefs, config);
                settings.setTourShown(true);
                I18n.setLanguage("en");
                Theme.LIGHT.install();
                MainFrame frame = new MainFrame(settings);
                frame.setVisible(true);
                frame.openExample();
                waitForResult(frame, config, 0);
            } catch (Exception e) {
                fail(e.toString(), e);
            }
        });
    }

    private static void waitForResult(MainFrame frame, Path config, int attempt) {
        ComparisonTab tab = frame.selectedComparison();
        DiffResult r = tab == null ? null : tab.result();
        if (r == null) {
            if (attempt > 600) {
                fail("the example comparison did not finish", null);
            }
            Timer t = new Timer(100, _ -> waitForResult(frame, config, attempt + 1));
            t.setRepeats(false);
            t.start();
            return;
        }
        try {
            check(r.rows().size() > 0, "the comparison has rows");
            check(!r.insights().newProblems().isEmpty(), "the example has new problems");
            check(r.problemTimeline() != null && r.problemTimeline().hasProblems(), "the problem timeline has data");

            List<MenuCommands.Entry> commands = MenuCommands.collect(frame.getJMenuBar());
            check(commands.size() > 40, "the command palette finds the menu commands (" + commands.size() + ")");
            check(commands.stream().anyMatch(c -> c.accelerator() != null && c.accelerator().equals(MainFrame.PALETTE_KEY)),
                    "the command palette has its shortcut");

            for (Theme theme : Theme.values()) {
                frame.useTheme(theme);
                paint(frame);
            }
            frame.useTheme(Theme.LIGHT);

            Path report = config.resolve("smoke.html");
            Reports.writeFile(r.redacted(r.options().redactor()), ReportFormat.HTML, ReportOptions.DEFAULT, report);
            check(Files.size(report) > 1000, "an HTML report can be written");

            I18n.setLanguage("ko");
            check(!I18n.tr("Open Logs…").equals("Open Logs…"), "the Korean translation loads");
            I18n.setLanguage("en");

            System.out.println("smoke test passed: " + r.rows().size() + " rows, " + commands.size() + " commands");
            frame.dispose();
            System.exit(0);
        } catch (Exception | AssertionError e) {
            fail(e.toString(), e);
        }
    }

    private static void paint(MainFrame frame) {
        BufferedImage img = new BufferedImage(Math.max(1, frame.getWidth()), Math.max(1, frame.getHeight()),
                BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        frame.paintAll(g);
        g.dispose();
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("check failed: " + what);
        }
        System.out.println("ok - " + what);
    }

    private static void fail(String message, Throwable e) {
        System.err.println("smoke test FAILED: " + message);
        if (e != null) {
            e.printStackTrace();
        }
        System.exit(1);
    }
}
