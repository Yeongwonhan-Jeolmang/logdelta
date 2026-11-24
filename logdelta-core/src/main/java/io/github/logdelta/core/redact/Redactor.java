// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.redact;

import io.github.logdelta.core.normalize.InvalidRuleException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/// Removes secrets and personal data from text before it leaves LogDelta in a report, a
/// copied summary or a CI annotation.
///
/// The built-in detectors find:
///
/// - `Authorization:` headers and bearer tokens, JSON Web Tokens;
/// - `password=`, `secret:`, `api_key=`, `token=` and similar key/value pairs;
/// - credentials in URLs (`https://user:pass@host`);
/// - private-key blocks;
/// - well-known token formats: AWS access key ids, GitHub, GitLab, Slack, Google, Stripe, npm and
///   `sk-` style API keys;
/// - e-mail addresses.
///
/// Each finding is replaced by [#REPLACEMENT]; the key part (`password=`, `Bearer `) is kept so
/// the report still reads naturally. Extra patterns can be added with [#withPatterns(List)]: the
/// whole match is redacted, or only the named group `secret` if the pattern has one.
///
/// Instances are immutable and thread-safe.
public final class Redactor {

    /// Text that replaces each secret.
    public static final String REPLACEMENT = "[REDACTED]";

    private static final List<String> SECRET_GROUPS = List.of("secret", "quoted", "single");

    private static final List<Detector> BUILTIN = List.of(
            new Detector("private-key", "Private key",
                    "-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)"),
            new Detector("authorization", "Authorization header",
                    "(?i)\\b(?:proxy-)?authorization[\"']?\\s*[:=]\\s*[\"']?"
                            + "(?:(?:bearer|basic|token|digest|negotiate|apikey)\\s+)?(?<secret>[^\\s\"',;]+)"),
            new Detector("bearer", "Bearer token", "(?i)\\bbearer\\s+(?<secret>[A-Za-z0-9\\-._~+/]{8,}=*)"),
            new Detector("jwt", "JSON Web Token",
                    "\\beyJ[A-Za-z0-9_-]{5,}\\.eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}"),
            new Detector("url-credentials", "Credentials in a URL",
                    "(?<=://)(?<secret>[^/\\s:@\"']+:[^/\\s@\"']+)(?=@)"),
            new Detector("key-value", "Secret key/value pair",
                    "(?i)\\b(?:password|passwd|pwd|pass|passphrase|secret|client[_-]?secret|api[_-]?key|apikey"
                            + "|access[_-]?key(?:[_-]?id)?|secret[_-]?(?:access[_-]?)?key|private[_-]?key"
                            + "|access[_-]?token|refresh[_-]?token|auth[_-]?token|id[_-]?token|session[_-]?token"
                            + "|token|credentials?|x-api-key)[\"']?\\s*[=:]\\s*"
                            // A quoted value is removed up to its closing quote, spaces included;
                            // `\"` inside a JSON string counts as the quote.
                            + "(?:\\\\?\"(?<quoted>(?:[^\"\\\\\\n]|\\\\[^\"])+)|'(?<single>[^'\\n]+)"
                            + "|(?!(?-i:[<\\[](?:[A-Z][A-Z0-9_:-]*|REDACTED)[>\\]]))(?<secret>[^\\s\"',;&}\\]]+))"),
            new Detector("aws-access-key", "AWS access key id", "\\b(?:AKIA|ASIA|AGPA|AIDA|AROA|ANPA)[A-Z0-9]{16}\\b"),
            new Detector("github-token", "GitHub token",
                    "\\b(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{22,255})\\b"),
            new Detector("gitlab-token", "GitLab token", "\\bglpat-[A-Za-z0-9_-]{20,}\\b"),
            new Detector("slack-token", "Slack token or webhook",
                    "\\bxox[abposr]-[A-Za-z0-9-]{10,}\\b|(?<=hooks\\.slack\\.com/services/)(?<secret>[A-Za-z0-9/]+)"),
            new Detector("google-api-key", "Google API key", "\\bAIza[0-9A-Za-z_-]{35}\\b"),
            new Detector("stripe-key", "Stripe key", "\\b(?:sk|rk|pk)_(?:live|test)_[A-Za-z0-9]{16,}\\b"),
            new Detector("sk-key", "API key (sk-...)", "\\bsk-(?:ant-|proj-|live-)?[A-Za-z0-9_-]{20,}"),
            new Detector("npm-token", "npm token", "\\bnpm_[A-Za-z0-9]{36}\\b"),
            new Detector("email", "E-mail address",
                    "(?:(?<=mailto:)|(?<![\\w.%+\\-:/]))(?!git@)[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,24}\\b"));

    private static final Redactor DEFAULTS = new Redactor(BUILTIN);
    private static final Redactor NONE = new Redactor(List.of());

    private final List<Detector> detectors;

    private Redactor(List<Detector> detectors) {
        this.detectors = List.copyOf(detectors);
    }

    /// The built-in detectors.
    public static Redactor defaults() {
        return DEFAULTS;
    }

    /// A redactor that changes nothing.
    public static Redactor none() {
        return NONE;
    }

    /// Returns a redactor with additional patterns.
    ///
    /// @throws InvalidRuleException if a pattern is not a valid regular expression
    public Redactor withPatterns(List<String> patterns) {
        if (patterns.isEmpty()) {
            return this;
        }
        List<Detector> all = new ArrayList<>(detectors);
        int n = 1;
        for (String p : patterns) {
            try {
                all.add(new Detector("custom-" + n++, "Custom pattern", p));
            } catch (PatternSyntaxException e) {
                throw new InvalidRuleException("Invalid redaction pattern '" + p + "': " + e.getDescription(), e);
            }
        }
        return new Redactor(all);
    }

    /// Ids and names of the built-in detectors, for documentation and help texts.
    public static List<String[]> builtinDetectors() {
        return BUILTIN.stream().map(d -> new String[] {d.id, d.name}).toList();
    }

    /// Whether this redactor never changes anything.
    public boolean isNone() {
        return detectors.isEmpty();
    }

    /// Redacts one piece of text.
    public Redaction redact(String text) {
        if (detectors.isEmpty() || text == null || text.isEmpty()) {
            return Redaction.unchanged(text);
        }
        List<int[]> ranges = new ArrayList<>();
        for (Detector d : detectors) {
            Matcher m = d.pattern.matcher(text);
            while (m.find()) {
                int s = m.start();
                int e = m.end();
                for (String g : d.secretGroups) {
                    if (m.start(g) >= 0) {
                        s = m.start(g);
                        e = m.end(g);
                        break;
                    }
                }
                if (e > s && !text.startsWith(REPLACEMENT, s)) {
                    ranges.add(new int[] {s, e});
                }
            }
        }
        if (ranges.isEmpty()) {
            return Redaction.unchanged(text);
        }
        ranges.sort(Comparator.comparingInt((int[] r) -> r[0]).thenComparingInt(r -> -r[1]));
        List<int[]> merged = new ArrayList<>();
        for (int[] r : ranges) {
            if (!merged.isEmpty() && r[0] <= merged.getLast()[1]) {
                merged.getLast()[1] = Math.max(merged.getLast()[1], r[1]);
            } else {
                merged.add(r.clone());
            }
        }
        return Redaction.of(text, merged, REPLACEMENT);
    }

    /// Shorthand for `redact(text).text()`.
    public String apply(String text) {
        return redact(text).text();
    }

    private static final class Detector {
        final String id;
        final String name;
        final Pattern pattern;
        /// Named groups that hold the secret itself (the rest of the match, such as `password=`,
        /// stays readable); without them the whole match is removed.
        final List<String> secretGroups;

        Detector(String id, String name, String regex) {
            this.id = id;
            this.name = name;
            this.pattern = Pattern.compile(regex);
            this.secretGroups = SECRET_GROUPS.stream().filter(g -> regex.contains("(?<" + g + ">")).toList();
        }
    }
}
