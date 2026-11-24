// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.theme;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.nimbus.NimbusLookAndFeel;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/// Application themes. Light, dark and high contrast are tuned variants of Nimbus (shipped with
/// every JDK); [#SYSTEM] uses the platform look and feel with the light diff palette.
///
/// Every theme except [#HIGH_CONTRAST] has a colour-blind-safe variant of its diff palette (see
/// [#palette(boolean)]).
public enum Theme {
    LIGHT("Light"),
    DARK("Dark"),
    HIGH_CONTRAST("High contrast"),
    SYSTEM("System");

    private static final List<String> MONO_FONTS = List.of("JetBrains Mono", "Cascadia Mono", "Cascadia Code",
            "SF Mono", "Menlo", "Consolas", "DejaVu Sans Mono", "Liberation Mono", "Ubuntu Mono");
    private static final List<String> UI_FONTS = List.of("Inter", "Segoe UI", "SF Pro Text", "Helvetica Neue",
            "Cantarell", "Noto Sans", "DejaVu Sans");

    private static String monoFamily;
    private static String uiFamily;

    private final String displayName;

    Theme(String displayName) {
        this.displayName = displayName;
    }

    /// Name shown in menus, in the active language.
    public String displayName() {
        return io.github.logdelta.gui.i18n.I18n.tr(displayName);
    }

    /// Colours for the comparison view.
    public DiffPalette palette() {
        return palette(false);
    }

    /// Colours for the comparison view.
    ///
    /// @param colorBlindSafe use colours that stay distinct with red-green colour blindness
    public DiffPalette palette(boolean colorBlindSafe) {
        return switch (this) {
            case DARK -> colorBlindSafe ? DiffPalette.DARK_CVD : DiffPalette.DARK;
            case HIGH_CONTRAST -> DiffPalette.HIGH_CONTRAST;
            case LIGHT, SYSTEM -> colorBlindSafe ? DiffPalette.LIGHT_CVD : DiffPalette.LIGHT;
        };
    }

    /// Whether the theme has dark backgrounds.
    public boolean isDark() {
        return this == DARK || this == HIGH_CONTRAST;
    }

    /// Parses a stored name, falling back to [#LIGHT].
    public static Theme parse(String s) {
        for (Theme t : values()) {
            if (t.name().equalsIgnoreCase(s)) {
                return t;
            }
        }
        return LIGHT;
    }

    /// Installs the look and feel for this theme and refreshes every open window. Must be called
    /// on the event dispatch thread.
    public void install() {
        try {
            if (this == SYSTEM) {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } else {
                resetNimbusColors();
                if (this == DARK) {
                    applyDarkNimbus();
                } else if (this == HIGH_CONTRAST) {
                    applyHighContrastNimbus();
                } else {
                    applyLightNimbus();
                }
                UIManager.put("defaultFont", new FontUIResource(uiFont(13f)));
                UIManager.setLookAndFeel(new NimbusLookAndFeel());
            }
        } catch (UnsupportedLookAndFeelException | ReflectiveOperationException _) {
            // Keep whatever look and feel is active; the application stays usable.
        }
        UIManager.put("ScrollBar.minimumThumbSize", new java.awt.Dimension(24, 24));
        for (Window w : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(w);
            w.repaint();
        }
    }

    private static final String[] NIMBUS_KEYS = {"control", "info", "nimbusBase", "nimbusBlueGrey",
        "nimbusLightBackground", "nimbusSelectionBackground", "nimbusSelectedText", "nimbusFocus", "text",
        "nimbusDisabledText", "menu", "menuText", "textForeground", "textBackground", "infoText",
        "nimbusInfoBlue", "nimbusAlertYellow", "nimbusRed", "nimbusOrange", "nimbusGreen", "nimbusBorder",
        "Table.alternateRowColor", "Table.gridColor", "List.background", "Table.background", "TextField.background",
        "TextArea.background", "TextPane.background", "EditorPane.background", "TableHeader.background",
        "ToolTip.background", "ToolTip.foreground"};

    private static void resetNimbusColors() {
        for (String k : NIMBUS_KEYS) {
            UIManager.put(k, null);
        }
    }

    private static void applyLightNimbus() {
        put("control", 0xF3F4F6);
        put("nimbusBase", 0x5B6573);
        put("nimbusBlueGrey", 0xB4BCC7);
        put("nimbusFocus", 0x4C8DF6);
        put("nimbusSelectionBackground", 0x2F6FDE);
        put("nimbusLightBackground", 0xFFFFFF);
        put("nimbusInfoBlue", 0x2F6FDE);
        put("info", 0xFFFFFF);
        put("text", 0x1F2328);
        put("Table.alternateRowColor", 0xF7F8FA);
        put("Table.gridColor", 0xE3E6EA);
    }

    private static void applyDarkNimbus() {
        put("control", 0x2B2D30);
        put("info", 0x2B2D30);
        put("nimbusBase", 0x18191B);
        put("nimbusBlueGrey", 0x3C3F44);
        put("nimbusLightBackground", 0x1E1F22);
        put("nimbusSelectionBackground", 0x2E4F8A);
        put("nimbusSelectedText", 0xFFFFFF);
        put("nimbusFocus", 0x4A7BD0);
        put("nimbusDisabledText", 0x6F737A);
        put("nimbusInfoBlue", 0x4A7BD0);
        put("nimbusAlertYellow", 0xC69026);
        put("nimbusRed", 0xC9504A);
        put("nimbusOrange", 0xC97B2A);
        put("nimbusGreen", 0x57AB5A);
        put("nimbusBorder", 0x393B40);
        put("text", 0xDFE1E5);
        put("menuText", 0xDFE1E5);
        put("textForeground", 0xDFE1E5);
        put("infoText", 0xDFE1E5);
        put("Table.alternateRowColor", 0x232427);
        put("Table.gridColor", 0x393B40);
        put("ToolTip.background", 0x2B2D30);
        put("ToolTip.foreground", 0xDFE1E5);
    }

    private static void applyHighContrastNimbus() {
        put("control", 0x000000);
        put("info", 0x000000);
        put("nimbusBase", 0x000000);
        put("nimbusBlueGrey", 0x5A5A5A);
        put("nimbusLightBackground", 0x000000);
        put("nimbusSelectionBackground", 0x00007A);
        put("nimbusSelectedText", 0xFFFFFF);
        put("nimbusFocus", 0xFFFF00);
        put("nimbusDisabledText", 0xA0A0A0);
        put("nimbusInfoBlue", 0x00A0FF);
        put("nimbusAlertYellow", 0xFFFF00);
        put("nimbusRed", 0xFF5050);
        put("nimbusOrange", 0xFF9900);
        put("nimbusGreen", 0x50FF80);
        put("nimbusBorder", 0xFFFFFF);
        put("text", 0xFFFFFF);
        put("menuText", 0xFFFFFF);
        put("textForeground", 0xFFFFFF);
        put("infoText", 0xFFFFFF);
        put("Table.alternateRowColor", 0x141414);
        put("Table.gridColor", 0x808080);
        put("ToolTip.background", 0x000000);
        put("ToolTip.foreground", 0xFFFFFF);
    }

    private static void put(String key, int rgb) {
        UIManager.put(key, new ColorUIResource(new Color(rgb)));
    }

    /// The best available monospaced font at the given size.
    public static Font monoFont(float size) {
        if (monoFamily == null) {
            monoFamily = pick(MONO_FONTS, Font.MONOSPACED);
        }
        return new Font(monoFamily, Font.PLAIN, Math.round(size)).deriveFont(size);
    }

    /// The best available UI font at the given size. When the preferred fonts cannot show the
    /// interface language (Korean, for example), Java's logical "Dialog" font is used instead: it
    /// falls back to other installed fonts glyph by glyph.
    public static Font uiFont(float size) {
        if (uiFamily == null) {
            uiFamily = pick(UI_FONTS, Font.SANS_SERIF);
            String sample = languageSample(io.github.logdelta.gui.i18n.I18n.language());
            if (sample != null && new Font(uiFamily, Font.PLAIN, 13).canDisplayUpTo(sample) >= 0) {
                uiFamily = Font.DIALOG;
            }
        }
        return new Font(uiFamily, Font.PLAIN, Math.round(size)).deriveFont(size);
    }

    /// Characters a UI font must be able to show for a language, or `null` for Latin scripts.
    static String languageSample(String language) {
        return switch (language) {
            case "ko" -> "\uD55C\uAD6D\uC5B4\uADDC\uCE59";
            default -> null;
        };
    }

    private static String pick(List<String> preferred, String fallback) {
        Set<String> available = Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames(Locale.ROOT)).collect(Collectors.toSet());
        for (String f : preferred) {
            if (available.contains(f)) {
                return f;
            }
        }
        return fallback;
    }
}
