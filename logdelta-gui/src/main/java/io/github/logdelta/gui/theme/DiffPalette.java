// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.theme;

import java.awt.Color;

/// Colours used to paint the comparison. One instance per [Theme].
///
/// @param background      editor background
/// @param text            regular text
/// @param lineNumber      line numbers
/// @param gutter          line-number gutter background
/// @param divider         separators between panes and around the gutter
/// @param removed         background of removed text
/// @param removedStrong   background of removed words within a modified line
/// @param removedMarker   marker/accent colour for removals
/// @param added           background of added text
/// @param addedStrong     background of added words within a modified line
/// @param addedMarker     marker/accent colour for additions
/// @param modifiedMarker  marker colour for modified rows
/// @param moved           background of moved lines
/// @param movedMarker     accent colour for moved lines
/// @param empty           background of the side of a row that has no line
/// @param emptyHatch      hatching drawn on empty sides
/// @param fold            background of collapsed-region rows
/// @param foldText        text of collapsed-region rows
/// @param maskText        text colour of masked (normalized) regions
/// @param maskUnderline   dotted underline under masked regions
/// @param selection       background of the selected row
/// @param selectionBorder outline of the selected row
/// @param searchMatch     background of search matches
/// @param searchCurrent   background of the current search match
/// @param problem         accent used for errors in the overview ruler and lists
/// @param bookmark        bookmark markers
/// @param slowPhase       bar beside rows inside a phase that got notably slower
/// @param syntax          colours for timestamps, levels, threads and logger names
public record DiffPalette(Color background, Color text, Color lineNumber, Color gutter, Color divider,
                          Color removed, Color removedStrong, Color removedMarker,
                          Color added, Color addedStrong, Color addedMarker, Color modifiedMarker,
                          Color moved, Color movedMarker, Color empty, Color emptyHatch,
                          Color fold, Color foldText, Color maskText, Color maskUnderline,
                          Color selection, Color selectionBorder, Color searchMatch, Color searchCurrent,
                          Color problem, Color bookmark, Color slowPhase, Syntax syntax) {

    /// Syntax colours for the structural parts of log lines (see
    /// [io.github.logdelta.gui.view.SyntaxHighlighter]).
    ///
    /// @param timestamp timestamps
    /// @param thread    thread names
    /// @param logger    logger names
    /// @param error     ERROR/FATAL levels and exception names
    /// @param warn      WARN levels
    /// @param info      INFO levels
    /// @param debug     DEBUG/TRACE levels
    /// @param stack     stack-trace frames
    public record Syntax(Color timestamp, Color thread, Color logger, Color error, Color warn, Color info,
                         Color debug, Color stack) {
    }

    /// GitHub-like light palette.
    public static final DiffPalette LIGHT = new DiffPalette(
            rgb(0xFFFFFF), rgb(0x1F2328), rgb(0x8C959F), rgb(0xF6F8FA), rgb(0xD8DEE4),
            rgb(0xFFEBE9), rgb(0xFFB8B0), rgb(0xCF222E),
            rgb(0xE6FFEC), rgb(0xA6EBB8), rgb(0x1A7F37), rgb(0x9A6700),
            rgb(0xF4EFFF), rgb(0x8250DF), rgb(0xF6F8FA), rgb(0xE8ECF0),
            rgb(0xF0F3F6), rgb(0x57606A), rgb(0x6E7781), rgb(0xAFB8C1),
            rgb(0xDDF4FF), rgb(0x54AEFF), rgb(0xFFF1A8), rgb(0xFFC93D),
            rgb(0xCF222E), rgb(0xBF8700), rgb(0xE16F24),
            new Syntax(rgb(0x0E7490), rgb(0x8C959F), rgb(0x6639BA), rgb(0xCF222E), rgb(0xBC4C00),
                    rgb(0x1A7F37), rgb(0x8C959F), rgb(0x8C959F)));

    /// Low-glare dark palette.
    public static final DiffPalette DARK = new DiffPalette(
            rgb(0x1E1F22), rgb(0xDFE1E5), rgb(0x6F737A), rgb(0x232427), rgb(0x393B40),
            rgb(0x3D2427), rgb(0x713236), rgb(0xF47067),
            rgb(0x1F3326), rgb(0x2C6239), rgb(0x57AB5A), rgb(0xC69026),
            rgb(0x2B2640), rgb(0xA88BFA), rgb(0x232427), rgb(0x2C2E33),
            rgb(0x26282C), rgb(0x8B9098), rgb(0x8C9097), rgb(0x5A5E66),
            rgb(0x26344F), rgb(0x4A7BD0), rgb(0x5C4B13), rgb(0x8F6F0E),
            rgb(0xF47067), rgb(0xE3B341), rgb(0xF0883E),
            new Syntax(rgb(0x56B6C2), rgb(0x7D828A), rgb(0xC0A2F7), rgb(0xF47067), rgb(0xF0A35E),
                    rgb(0x7EC699), rgb(0x7D828A), rgb(0x7D828A)));

    private static Color rgb(int v) {
        return new Color(v);
    }
}
