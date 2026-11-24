// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.util;

import io.github.logdelta.core.Version;
import io.github.logdelta.core.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Looks for a newer release on GitHub.
///
/// Nothing is sent but an anonymous `GET` of the public "latest release" API: no identifiers,
/// no usage data. Automatic checks are off until the user turns them on (opt-in), and then run at
/// most once a day.
public final class UpdateChecker {

    /// The GitHub API endpoint for the latest release.
    public static final URI LATEST = URI.create(
            "https://api.github.com/repos/Yeongwonhan-Jeolmang/logdelta/releases/latest");
    /// Minimum time between automatic checks.
    public static final Duration INTERVAL = Duration.ofDays(1);

    private static final Pattern NUMBERS = Pattern.compile("(\\d+(?:\\.\\d+)*)");

    private UpdateChecker() {
    }

    /// The outcome of a check.
    ///
    /// @param latestVersion version of the latest release, e.g. `2.1.0`
    /// @param name          release title
    /// @param url           page of the release
    /// @param newer         whether it is newer than the running version
    public record Result(String latestVersion, String name, String url, boolean newer) {
    }

    /// Asks GitHub for the latest release. Blocks; call it off the event dispatch thread.
    ///
    /// @throws IOException if the request fails or the answer cannot be read
    public static Result check() throws IOException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(LATEST).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", Version.NAME + "/" + Version.VERSION)
                .GET().build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (response.statusCode() == 404) {
            throw new IOException("No release has been published yet");
        }
        if (response.statusCode() != 200) {
            throw new IOException("GitHub answered " + response.statusCode());
        }
        return parse(response.body(), Version.VERSION);
    }

    /// Reads a GitHub release JSON document. Package-private for tests.
    static Result parse(String json, String running) throws IOException {
        Object doc;
        try {
            doc = Json.parse(json);
        } catch (IllegalArgumentException e) {
            throw new IOException("Unexpected answer from GitHub", e);
        }
        String tag = Json.string(doc, "tag_name");
        String url = Json.string(doc, "html_url");
        String name = Json.string(doc, "name");
        if (tag == null) {
            throw new IOException("The release has no tag");
        }
        String latest = version(tag);
        return new Result(latest, name == null || name.isBlank() ? tag : name, url,
                compare(latest, version(running)) > 0);
    }

    /// The numeric part of a tag: `v2.0.0` → `2.0.0`, `v.1.1.0` → `1.1.0`.
    public static String version(String tag) {
        Matcher m = NUMBERS.matcher(tag);
        return m.find() ? m.group(1) : tag;
    }

    /// Compares dotted version numbers numerically (`2.10.0` > `2.9.1`); missing parts count as 0.
    public static int compare(String a, String b) {
        List<Integer> x = parts(a);
        List<Integer> y = parts(b);
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            int c = Integer.compare(i < x.size() ? x.get(i) : 0, i < y.size() ? y.get(i) : 0);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static List<Integer> parts(String v) {
        List<Integer> out = new ArrayList<>();
        for (String p : version(v).split("\\.")) {
            try {
                out.add(Integer.parseInt(p));
            } catch (NumberFormatException e) {
                out.add(0);
            }
        }
        return out;
    }
}
