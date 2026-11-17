// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.util;

/// Small string helpers shared by the report writers and front-ends.
public final class Text {

    private Text() {
    }

    /// Escapes `&`, `<`, `>`, `"` and `'` for safe HTML output.
    public static String escapeHtml(CharSequence s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /// Escapes text for XML 1.0 content and attribute values.
    ///
    /// Besides the five predefined entities, control characters that XML 1.0 forbids
    /// (everything below U+0020 except tab, newline and carriage return) are replaced
    /// with U+FFFD, so that raw log text can never produce a malformed document.
    public static String escapeXml(CharSequence s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                case '\t', '\n', '\r' -> sb.append(c);
                default -> sb.append(c < 0x20 || c == 0xFFFE || c == 0xFFFF ? '�' : c);
            }
        }
        return sb.toString();
    }

    /// Escapes characters that have a meaning inside Markdown table cells and inline text.
    public static String escapeMarkdown(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("\\`*_{}[]<>()#+!|".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /// Shortens `s` to at most `max` characters, replacing the tail with an ellipsis.
    public static String truncate(String s, int max) {
        if (max <= 0) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return max == 1 ? "…" : s.substring(0, max - 1) + "…";
    }

    /// Formats a byte count as a short human-readable string such as `"1.4 MB"`.
    public static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = -1;
        do {
            v /= 1024;
            u++;
        } while (v >= 1024 && u < units.length - 1);
        return String.format(java.util.Locale.ROOT, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[u]);
    }

    /// Replaces tab characters by spaces so that text lines up on a `tabSize` grid.
    public static String expandTabs(String s, int tabSize) {
        if (s.indexOf('\t') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t') {
                int spaces = tabSize - (sb.length() % tabSize);
                sb.append(" ".repeat(spaces));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
