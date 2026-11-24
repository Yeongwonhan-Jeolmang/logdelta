// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks that every text passed to `tr(...)` has a Korean translation with the same placeholders.
class I18nTest {

    private static final String LITERAL = "\"(?:[^\"\\\\\\n]|\\\\.)*\"";
    private static final Pattern CALL = Pattern.compile(
            "\\btr\\(\\s*(" + LITERAL + "(?:\\s*\\+\\s*" + LITERAL + ")*)");
    private static final Pattern PART = Pattern.compile(LITERAL);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    static Path sources() {
        String module = System.getProperty("basedir");
        if (module != null && Files.isDirectory(Path.of(module, "src/main/java"))) {
            return Path.of(module, "src/main/java");
        }
        return Path.of(System.getProperty("basedir.root", ".."), "logdelta-gui/src/main/java");
    }

    /// Every literal text given to `tr`, mapped to the first file that uses it.
    static Map<String, String> literals() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        Path root = sources();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                Matcher m = CALL.matcher(Files.readString(f));
                while (m.find()) {
                    out.putIfAbsent(decode(m.group(1)), root.relativize(f).toString());
                }
            }
        }
        return out;
    }

    private static String decode(String concatenation) {
        StringBuilder sb = new StringBuilder();
        Matcher m = PART.matcher(concatenation);
        while (m.find()) {
            String body = m.group();
            body = body.substring(1, body.length() - 1);
            StringBuilder plain = new StringBuilder();
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c != '\\' || i + 1 >= body.length()) {
                    plain.append(c);
                    continue;
                }
                char n = body.charAt(++i);
                switch (n) {
                    case 'n' -> plain.append('\n');
                    case 't' -> plain.append('\t');
                    case 'u' -> {
                        plain.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    default -> plain.append(n);
                }
            }
            sb.append(plain);
        }
        return sb.toString();
    }

    private static TreeSet<String> placeholders(String s) {
        TreeSet<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(s);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    @Test
    @DisplayName("every tr(...) text has a Korean translation")
    void everythingTranslated() throws IOException {
        Map<String, String> literals = literals();
        assertTrue(literals.size() > 300, "found only " + literals.size() + " texts in " + sources());
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : literals.entrySet()) {
            if (!I18n.isTranslated("ko", e.getKey())) {
                missing.add(e.getValue() + ": " + e.getKey());
            }
        }
        assertTrue(missing.isEmpty(), "Missing Korean translations:\n" + String.join("\n", missing));
    }

    @Test
    @DisplayName("translations keep the same {0} placeholders")
    void placeholdersMatch() {
        Properties ko = I18n.translations("ko");
        List<String> wrong = new ArrayList<>();
        for (String key : ko.stringPropertyNames()) {
            if (!placeholders(key).equals(placeholders(ko.getProperty(key)))) {
                wrong.add(key + " -> " + ko.getProperty(key));
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    @DisplayName("tr substitutes arguments and falls back to English")
    void substitution() {
        String before = I18n.language();
        try {
            I18n.setLanguage("en");
            assertEquals("3 of 7", I18n.tr("{0} of {1}", 3, 7));
            assertEquals("no such text", I18n.tr("no such text"));
            assertEquals("{0} {0}", I18n.tr("{0} {0}", "{0}"), "arguments are not substituted again");
            assertEquals("{1} and x", I18n.tr("{0} and {1}", "{1}", "x"));
            assertEquals("{2} {}", I18n.tr("{2} {}", "a"), "unknown placeholders stay");
            I18n.setLanguage("ko");
            assertEquals("no such text", I18n.tr("no such text"));
            assertEquals("ko", I18n.language());
            I18n.setLanguage("xx");
            assertEquals("en", I18n.language());
        } finally {
            I18n.setLanguage(before);
        }
    }
}
