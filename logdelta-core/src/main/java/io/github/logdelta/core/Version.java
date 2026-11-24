// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core;

/// Build and product information.
///
/// [#VERSION] is kept in sync with the Maven project version by the release process
/// (see `CONTRIBUTING.md`).
public final class Version {

    /// Product name as shown to users.
    public static final String NAME = "LogDelta";

    /// Semantic version of this build.
    public static final String VERSION = "2.0.0";

    /// Project home page.
    public static final String HOMEPAGE = "https://github.com/Yeongwonhan-Jeolmang/logdelta";

    private Version() {
    }

    /// Returns `"LogDelta 2.0.0"`.
    public static String displayName() {
        return NAME + " " + VERSION;
    }
}
