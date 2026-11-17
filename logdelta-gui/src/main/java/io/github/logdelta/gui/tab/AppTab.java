// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.tab;

import javax.swing.JComponent;

/// A tab of the main window: a comparison of two logs or of two folders.
///
/// The window owns the menus, the toolbar and the status bar; each tab owns its own state and
/// tells the window through its [TabHost] when the title, the status or the available actions
/// change.
public sealed interface AppTab permits ComparisonTab, FolderTab {

    /// The component shown in the tab.
    JComponent component();

    /// Short title for the tab strip.
    String title();

    /// Longer description for the tab's tooltip.
    String tooltip();

    /// Message for the status bar while the tab is selected.
    String statusMessage();

    /// Secondary status text (right-aligned), may be empty.
    String statusDetails();

    /// Whether a background task is running.
    boolean isBusy();

    /// Name of the running stage, for the progress bar.
    String busyStage();

    /// Progress of the running stage in `[0, 1]`, or a negative value if unknown.
    double busyFraction();

    /// Cancels the running task, if any.
    void cancel();

    /// Asks whether the tab may be closed (e.g. about unsaved notes).
    ///
    /// @return `false` if the user wants to keep the tab open
    boolean confirmClose();

    /// Releases resources (watchers, timers, background tasks).
    void dispose();
}
