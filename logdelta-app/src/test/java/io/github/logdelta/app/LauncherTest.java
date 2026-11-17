// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LauncherTest {

    @Test
    @DisplayName("no arguments open the GUI, or print help without a display")
    void noArguments() {
        assertEquals(Launcher.Mode.GUI, Launcher.mode(new String[0], false, null));
        assertEquals(Launcher.Mode.HELP, Launcher.mode(new String[0], true, null));
    }

    @Test
    @DisplayName("'gui' selects the GUI, everything else the CLI")
    void dispatch() {
        assertEquals(Launcher.Mode.GUI, Launcher.mode(new String[] {"gui", "a.log", "b.log"}, false, null));
        assertEquals(Launcher.Mode.CLI, Launcher.mode(new String[] {"a.log", "b.log"}, false, null));
        assertEquals(Launcher.Mode.CLI, Launcher.mode(new String[] {"rules"}, true, null));
    }

    @Test
    @DisplayName("'cli' forces the command line and shows help when nothing follows")
    void cliLauncher() {
        assertEquals(Launcher.Mode.CLI, Launcher.mode(new String[] {"cli"}, false, null));
        assertEquals(java.util.List.of("--help"), java.util.List.of(Launcher.cliArgs(new String[] {"cli"})));
        assertEquals(java.util.List.of("a.log", "b.log"),
                java.util.List.of(Launcher.cliArgs(new String[] {"cli", "a.log", "b.log"})));
    }

    @Test
    @DisplayName("installer launchers pin the mode, so opened files reach the GUI")
    void installerLaunchers() {
        assertEquals(Launcher.Mode.GUI, Launcher.mode(new String[] {"review.ldsession"}, false, "gui"));
        assertEquals(Launcher.Mode.HELP, Launcher.mode(new String[] {"a.log"}, true, "gui"));
        assertEquals(Launcher.Mode.CLI, Launcher.mode(new String[0], false, "cli"));
        assertEquals(java.util.List.of("--help"), java.util.List.of(Launcher.cliArgs(new String[0])));
    }
}
