// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.preset;

import io.github.logdelta.core.normalize.Rule;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/// A built-in profile for a well-known log format (Spring Boot, nginx, Kubernetes, ...): a way to
/// recognise the format and the extra rules that hide its run-specific parts.
///
/// Presets only **add** rules. Their rules run before the general built-in rules, so they can
/// mask a whole prefix (such as the Kubernetes CRI header) before generic rules would split it.
///
/// @param id          stable identifier used in profiles and on the command line
/// @param name        human-readable name
/// @param description one line about what the preset recognises
/// @param signature   a line in this format matches this pattern (with `find()`)
/// @param rules       rules added when the preset is active
public record Preset(String id, String name, String description, Pattern signature, List<Rule> rules) {

    /// Validates and copies.
    public Preset {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(signature, "signature");
        rules = List.copyOf(rules);
    }

    /// Whether `line` looks like this format.
    public boolean matches(String line) {
        return signature.matcher(line).find();
    }

    @Override
    public String toString() {
        return name;
    }
}
