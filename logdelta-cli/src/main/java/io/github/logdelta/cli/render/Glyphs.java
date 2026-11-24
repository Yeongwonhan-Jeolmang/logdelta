// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.render;

/// Box-drawing and symbol characters, with an ASCII fallback for terminals whose encoding cannot
/// represent them (for example a POSIX locale or a legacy Windows code page).
///
/// @param vertical   column separator
/// @param horizontal rule line
/// @param cross      rule/separator junction
/// @param ellipsis   truncation marker
/// @param arrow      "becomes" arrow
/// @param moved      marker for moved lines
/// @param fold       marker for collapsed unchanged lines
public record Glyphs(String vertical, String horizontal, String cross, String ellipsis, String arrow,
                     String moved, String fold) {

    /// Unicode box drawing.
    public static final Glyphs UNICODE = new Glyphs("│", "─", "┼", "…", "→", "↕",
            "⋯");

    /// Plain ASCII.
    public static final Glyphs ASCII = new Glyphs("|", "-", "+", ">", "->", "^", "...");

    /// Characters for sparklines, from empty to full.
    public String sparks() {
        return this == UNICODE || vertical.equals("│") ? " ▁▂▃▄▅▆▇█" : " .:-=+*#@";
    }

    /// Returns [#UNICODE] or [#ASCII].
    public static Glyphs of(boolean unicode) {
        return unicode ? UNICODE : ASCII;
    }
}
