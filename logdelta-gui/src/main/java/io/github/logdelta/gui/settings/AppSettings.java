// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.settings;

import io.github.logdelta.core.config.ProfileIO;
import io.github.logdelta.core.config.ProfileParseException;
import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.gui.model.Source;
import io.github.logdelta.gui.theme.Theme;

import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/// Persistent user settings.
///
/// Small values live in [Preferences] (the registry on Windows, a plist on macOS, an XML
/// file under `~/.java` on Linux). The active rules and options are stored as a regular
/// profile file in `~/.logdelta/session.ldprofile`, so they can be inspected, edited and
/// shared like any other profile.
public final class AppSettings {

    /// Maximum number of remembered comparisons.
    public static final int MAX_RECENT = 10;

    private static final String THEME = "theme";
    private static final String FONT_SIZE = "fontSize";
    private static final String COLLAPSE = "collapseUnchanged";
    private static final String CONTEXT = "contextLines";
    private static final String SHOW_NORMALIZED = "showNormalized";
    private static final String FOLLOW = "followChanges";
    private static final String LAST_DIR = "lastDirectory";
    private static final String RECENT = "recent.";
    private static final String BOUNDS = "windowBounds";
    private static final String MAXIMIZED = "windowMaximized";
    private static final String DIVIDER = "sidebarWidth";
    private static final String PANE_SPLIT = "paneSplit";
    private static final String WRAP = "wrapLines";
    private static final String SYNTAX = "syntaxColoring";
    private static final String ELAPSED = "showElapsedTime";
    private static final String TOUR = "tourShownVersion";
    /// Bumped when the tour gains content worth showing to existing users.
    private static final int TOUR_VERSION = 1;

    private final Preferences prefs;
    private final Path sessionProfile;

    /// Settings stored under the given preferences node and configuration directory.
    public AppSettings(Preferences prefs, Path configDir) {
        this.prefs = prefs;
        this.sessionProfile = configDir.resolve("session." + ProfileIO.EXTENSION);
    }

    /// The settings of the current user.
    public static AppSettings load() {
        return new AppSettings(Preferences.userRoot().node("io/github/logdelta"),
                Path.of(System.getProperty("user.home"), ".logdelta"));
    }

    public Theme theme() {
        return Theme.parse(prefs.get(THEME, Theme.LIGHT.name()));
    }

    public void setTheme(Theme t) {
        prefs.put(THEME, t.name());
    }

    /// Editor font size in points.
    public float fontSize() {
        return Math.clamp(prefs.getFloat(FONT_SIZE, 13f), 8f, 32f);
    }

    public void setFontSize(float size) {
        prefs.putFloat(FONT_SIZE, size);
    }

    public boolean collapseUnchanged() {
        return prefs.getBoolean(COLLAPSE, true);
    }

    public void setCollapseUnchanged(boolean v) {
        prefs.putBoolean(COLLAPSE, v);
    }

    public int contextLines() {
        return prefs.getInt(CONTEXT, 3);
    }

    public void setContextLines(int v) {
        prefs.putInt(CONTEXT, v);
    }

    public boolean showNormalized() {
        return prefs.getBoolean(SHOW_NORMALIZED, false);
    }

    public void setShowNormalized(boolean v) {
        prefs.putBoolean(SHOW_NORMALIZED, v);
    }

    /// Whether files are re-compared automatically when they change on disk.
    public boolean followChanges() {
        return prefs.getBoolean(FOLLOW, false);
    }

    public void setFollowChanges(boolean v) {
        prefs.putBoolean(FOLLOW, v);
    }

    /// Whether long lines wrap instead of scrolling horizontally.
    public boolean wrapLines() {
        return prefs.getBoolean(WRAP, false);
    }

    public void setWrapLines(boolean v) {
        prefs.putBoolean(WRAP, v);
    }

    /// Whether timestamps, levels, threads and loggers are coloured.
    public boolean syntaxColoring() {
        return prefs.getBoolean(SYNTAX, true);
    }

    public void setSyntaxColoring(boolean v) {
        prefs.putBoolean(SYNTAX, v);
    }

    /// Whether the elapsed-time column is shown next to line numbers.
    public boolean showElapsedTime() {
        return prefs.getBoolean(ELAPSED, false);
    }

    public void setShowElapsedTime(boolean v) {
        prefs.putBoolean(ELAPSED, v);
    }

    /// Whether the first-run tour has been shown (or skipped) for this major version.
    public boolean tourShown() {
        return prefs.getInt(TOUR, 0) >= TOUR_VERSION;
    }

    /// Marks the tour as shown.
    public void setTourShown(boolean shown) {
        if (shown) {
            prefs.putInt(TOUR, TOUR_VERSION);
        } else {
            prefs.remove(TOUR);
        }
    }

    /// Directory last used in a file chooser, or `null`.
    public Path lastDirectory() {
        String s = prefs.get(LAST_DIR, null);
        return s == null ? null : Path.of(s);
    }

    public void setLastDirectory(Path dir) {
        if (dir != null) {
            prefs.put(LAST_DIR, dir.toString());
        }
    }

    /// Share of the comparison width given to the baseline pane (0.5 = equal panes).
    public double paneSplit() {
        double v = prefs.getDouble(PANE_SPLIT, 0.5);
        return Double.isFinite(v) ? Math.clamp(v, 0.1, 0.9) : 0.5;
    }

    public void setPaneSplit(double v) {
        prefs.putDouble(PANE_SPLIT, v);
    }

    /// Sidebar width in pixels.
    public int sidebarWidth() {
        return prefs.getInt(DIVIDER, 340);
    }

    public void setSidebarWidth(int w) {
        prefs.putInt(DIVIDER, w);
    }

    /// Saved window bounds, or `null`.
    public Rectangle windowBounds() {
        String s = prefs.get(BOUNDS, null);
        if (s == null) {
            return null;
        }
        String[] p = s.split(",");
        try {
            return new Rectangle(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                    Integer.parseInt(p[3]));
        } catch (RuntimeException _) {
            return null;
        }
    }

    public void setWindowBounds(Rectangle r) {
        prefs.put(BOUNDS, r.x + "," + r.y + "," + r.width + "," + r.height);
    }

    public boolean windowMaximized() {
        return prefs.getBoolean(MAXIMIZED, false);
    }

    public void setWindowMaximized(boolean v) {
        prefs.putBoolean(MAXIMIZED, v);
    }

    /// Recently compared file pairs, most recent first.
    public List<RecentPair> recent() {
        List<RecentPair> out = new ArrayList<>();
        for (int i = 0; i < MAX_RECENT; i++) {
            String s = prefs.get(RECENT + i, null);
            if (s == null) {
                break;
            }
            RecentPair p = RecentPair.parse(s);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    /// Moves `pair` to the front of the recent list.
    public void addRecent(RecentPair pair) {
        List<RecentPair> list = recent();
        list.remove(pair);
        list.addFirst(pair);
        for (int i = 0; i < MAX_RECENT; i++) {
            if (i < list.size()) {
                prefs.put(RECENT + i, list.get(i).format());
            } else {
                prefs.remove(RECENT + i);
            }
        }
    }

    /// Forgets all recent comparisons.
    public void clearRecent() {
        for (int i = 0; i < MAX_RECENT; i++) {
            prefs.remove(RECENT + i);
        }
    }

    /// Options from the last session, or the defaults if none were saved or the file is invalid.
    public DiffOptions sessionOptions() {
        if (!Files.isRegularFile(sessionProfile)) {
            return DiffOptions.defaults();
        }
        try {
            return ProfileIO.read(sessionProfile);
        } catch (IOException | ProfileParseException _) {
            return DiffOptions.defaults();
        }
    }

    /// Stores the active options for the next session. Failures are ignored.
    public void saveSessionOptions(DiffOptions options) {
        try {
            Files.createDirectories(sessionProfile.getParent());
            ProfileIO.write(options, sessionProfile);
        } catch (IOException _) {
            // Not critical: the next session simply starts with defaults.
        }
    }

    /// Writes pending preference changes to the backing store.
    public void flush() {
        try {
            prefs.flush();
        } catch (BackingStoreException _) {
            // Preferences are best-effort.
        }
    }

    /// A remembered comparison.
    ///
    /// @param left  baseline path or URL
    /// @param right candidate path or URL
    public record RecentPair(String left, String right) {

        static RecentPair parse(String s) {
            int sep = s.indexOf('\n');
            if (sep < 0) {
                return null;
            }
            try {
                RecentPair p = new RecentPair(s.substring(0, sep), s.substring(sep + 1));
                p.leftSource();
                p.rightSource();
                return p;
            } catch (RuntimeException _) {
                return null;
            }
        }

        String format() {
            return left + "\n" + right;
        }

        /// The baseline as a [Source].
        public Source leftSource() {
            return Source.parse(left);
        }

        /// The candidate as a [Source].
        public Source rightSource() {
            return Source.parse(right);
        }

        /// Short label such as `"good.log  vs  bad.log"`.
        public String label() {
            return leftSource().displayName() + "  vs  " + rightSource().displayName();
        }
    }
}
