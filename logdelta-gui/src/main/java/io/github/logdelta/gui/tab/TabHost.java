// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.tab;

import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.gui.model.Source;
import io.github.logdelta.gui.settings.AppSettings;
import io.github.logdelta.gui.theme.Theme;

import javax.swing.JFileChooser;
import java.nio.file.Path;

/// What a tab needs from the main window.
public interface TabHost {

    /// Persisted settings.
    AppSettings settings();

    /// The active theme.
    Theme theme();

    /// The tab's title, status, busy state or available actions changed.
    void tabChanged(AppTab tab);

    /// A comparison finished for the first time; remember it in the recent list.
    void remember(Source left, Source right);

    /// A file chooser in the last used directory.
    JFileChooser chooser(String title);

    /// Opens a comparison of two files in a new tab.
    void openInNewTab(Path left, Path right, DiffOptions options);
}
