// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.engine;

import io.github.logdelta.core.preset.Preset;
import io.github.logdelta.core.structured.LogFormat;

/// What LogDelta decided about the logs of a comparison.
///
/// @param format         how lines were read: [LogFormat#PLAIN], [LogFormat#JSON] or [LogFormat#LOGFMT]
/// @param preset         the built-in profile whose rules were added, or `null`
/// @param formatDetected whether the format was detected rather than configured
/// @param presetDetected whether the preset was detected rather than configured
public record Detection(LogFormat format, Preset preset, boolean formatDetected, boolean presetDetected) {

    /// Plain text without a preset.
    public static final Detection PLAIN = new Detection(LogFormat.PLAIN, null, false, false);

    /// A short description such as `"JSON lines (detected)"` or `"Spring Boot (detected)"`, or an
    /// empty string for plain text without a preset.
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (format.isStructured()) {
            sb.append(format.displayName()).append(formatDetected ? " (detected)" : "");
        }
        if (preset != null) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(preset.name()).append(presetDetected ? " (detected)" : "");
        }
        return sb.toString();
    }
}
