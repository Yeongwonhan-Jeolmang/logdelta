// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/// Translations of the desktop application.
///
/// The English text is the key: code says `tr("Open Logs…")` and gets the text in the active
/// language, or the English text itself when there is no translation. Translations live in
/// `messages_<language>.properties` next to this class (UTF-8). Placeholders `{0}`, `{1}`, ...
/// are replaced by the arguments; unlike [java.text.MessageFormat], apostrophes need no
/// escaping and numbers are inserted as given (format them first).
///
/// The language is chosen once at start-up ([#setLanguage(String)]); changing it takes effect
/// after a restart, like most desktop applications.
public final class I18n {

    /// Languages with a translation, as `{code, native name}` (English first).
    public static final List<String[]> LANGUAGES = List.of(
            new String[] {"en", "English"},
            new String[] {"ko", "한국어"});

    private static final String BUNDLE = "messages_%s.properties";
    private static final Map<String, Properties> CACHE = new ConcurrentHashMap<>();
    private static volatile Properties active = new Properties();
    private static volatile String language = "en";

    private I18n() {
    }

    /// Selects the language: a code from [#LANGUAGES], or `system` (or `null`) for the
    /// operating system's language when it has a translation.
    public static void setLanguage(String code) {
        String c = code == null || code.isBlank() || code.equals("system")
                ? Locale.getDefault().getLanguage() : code;
        Properties p = load(c);
        language = p.isEmpty() ? "en" : c;
        active = p;
    }

    /// The active language code (`en` when untranslated).
    public static String language() {
        return language;
    }

    /// The text in the active language, with `{0}`, `{1}`, ... replaced by `args`.
    public static String tr(String english, Object... args) {
        String t = active.getProperty(english, english);
        if (args.length == 0) {
            return t;
        }
        // One pass, so that an argument containing "{1}" is not replaced again.
        StringBuilder sb = new StringBuilder(t.length() + 16);
        int i = 0;
        while (i < t.length()) {
            int close;
            if (t.charAt(i) == '{' && (close = t.indexOf('}', i)) > i + 1 && close - i <= 3
                    && t.substring(i + 1, close).chars().allMatch(Character::isDigit)) {
                int n = Integer.parseInt(t, i + 1, close, 10);
                if (n < args.length) {
                    sb.append(args[n]);
                    i = close + 1;
                    continue;
                }
            }
            sb.append(t.charAt(i++));
        }
        return sb.toString();
    }

    /// Whether `english` has a translation in the given language; used by tests.
    public static boolean isTranslated(String languageCode, String english) {
        return load(languageCode).containsKey(english);
    }

    /// All translations of a language; used by tests.
    public static Properties translations(String languageCode) {
        Properties copy = new Properties();
        copy.putAll(load(languageCode));
        return copy;
    }

    private static Properties load(String code) {
        if (code.equals("en")) {
            return new Properties();
        }
        return CACHE.computeIfAbsent(code, c -> {
            Properties p = new Properties();
            try (InputStream in = I18n.class.getResourceAsStream(BUNDLE.formatted(c))) {
                if (in != null) {
                    p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            } catch (IOException _) {
                // fall back to English
            }
            return p;
        });
    }
}
