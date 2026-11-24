// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// A small, strict JSON reader (RFC 8259) with no dependencies.
///
/// [#parse(String)] returns plain Java values: `Map<String, Object>` (insertion-ordered),
/// `List<Object>`, `String`, `Long` or `Double`, `Boolean`, or `null`.
///
/// [#flattenObject(String, FieldVisitor)] walks a JSON object and reports every leaf with its
/// position in the text, which is what structured log comparison needs.
public final class Json {

    /// Maximum nesting depth; deeper input is rejected rather than risking a stack overflow.
    public static final int MAX_DEPTH = 64;

    private Json() {
    }

    /// Thrown for malformed JSON.
    public static final class ParseException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final int offset;

        ParseException(String message, int offset) {
            super(message + " at offset " + offset);
            this.offset = offset;
        }

        /// Offset in the input where the problem was found.
        public int offset() {
            return offset;
        }
    }

    /// Receives the leaves of an object, see [#flattenObject].
    @FunctionalInterface
    public interface FieldVisitor {
        /// One leaf.
        ///
        /// @param path       dotted path of the field (`http.status`)
        /// @param value      unescaped text for strings, literal text for numbers, booleans,
        ///                   `null` and arrays
        /// @param keyStart   offset of the opening quote of the (innermost) key
        /// @param valueStart offset of the first character of the value
        /// @param valueEnd   offset just after the value
        void field(String path, String value, int keyStart, int valueStart, int valueEnd);
    }

    /// Parses a complete JSON document.
    ///
    /// @throws ParseException if the text is not valid JSON
    public static Object parse(String text) {
        Cursor c = new Cursor(text);
        c.ws();
        Object v = c.value(0);
        c.ws();
        if (c.pos != text.length()) {
            throw c.error("Unexpected trailing content");
        }
        return v;
    }

    /// Walks a JSON object (possibly surrounded by whitespace) and reports each leaf.
    /// Nested objects are flattened into dotted paths; arrays are reported whole.
    ///
    /// @return `false` if `text` is not exactly one JSON object; leaves may already have been
    ///         reported in that case
    public static boolean flattenObject(String text, FieldVisitor visitor) {
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        if (start >= text.length() || text.charAt(start) != '{') {
            return false;
        }
        try {
            Cursor c = new Cursor(text);
            c.pos = start;
            c.flatten("", visitor, 0);
            c.ws();
            return c.pos == text.length();
        } catch (ParseException e) {
            return false;
        }
    }

    /// Returns the string member `name` of a parsed object, or `null`.
    public static String string(Object object, String name) {
        if (object instanceof Map<?, ?> m && m.get(name) instanceof String s) {
            return s;
        }
        return null;
    }

    private static final class Cursor {
        private final String s;
        private int pos;

        Cursor(String s) {
            this.s = s;
        }

        ParseException error(String message) {
            return new ParseException(message, pos);
        }

        void ws() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        char peek() {
            if (pos >= s.length()) {
                throw error("Unexpected end of input");
            }
            return s.charAt(pos);
        }

        void expect(char c) {
            if (peek() != c) {
                throw error("Expected '" + c + "'");
            }
            pos++;
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("Nested too deeply");
            }
            char c = peek();
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw error("Unexpected character '" + c + "'");
                }
            };
        }

        Map<String, Object> object(int depth) {
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            ws();
            if (peek() == '}') {
                pos++;
                return out;
            }
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                ws();
                out.put(key, value(depth + 1));
                ws();
                char c = peek();
                pos++;
                if (c == '}') {
                    return out;
                }
                if (c != ',') {
                    pos--;
                    throw error("Expected ',' or '}'");
                }
            }
        }

        List<Object> array(int depth) {
            expect('[');
            List<Object> out = new ArrayList<>();
            ws();
            if (peek() == ']') {
                pos++;
                return out;
            }
            while (true) {
                ws();
                out.add(value(depth + 1));
                ws();
                char c = peek();
                pos++;
                if (c == ']') {
                    return out;
                }
                if (c != ',') {
                    pos--;
                    throw error("Expected ',' or ']'");
                }
            }
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, pos)) {
                throw error("Invalid literal");
            }
            pos += word.length();
            return v;
        }

        Object number() {
            int start = pos;
            if (s.charAt(pos) == '-') {
                pos++;
            }
            if (pos < s.length() && s.charAt(pos) == '0') {
                pos++;
            } else {
                digits();
            }
            boolean fraction = false;
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                digits();
                fraction = true;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                digits();
                fraction = true;
            }
            String text = s.substring(start, pos);
            if (!fraction) {
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException e) {
                    // too large for a long: fall through to double
                }
            }
            return Double.parseDouble(text);
        }

        void digits() {
            int start = pos;
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                pos++;
            }
            if (pos == start) {
                throw error("Expected a digit");
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = null;
            int runStart = pos;
            while (true) {
                if (pos >= s.length()) {
                    throw error("Unterminated string");
                }
                char c = s.charAt(pos);
                if (c == '"') {
                    String out = sb == null ? s.substring(runStart, pos) : sb.append(s, runStart, pos).toString();
                    pos++;
                    return out;
                }
                if (c < 0x20) {
                    throw error("Control character in string");
                }
                if (c == '\\') {
                    if (sb == null) {
                        sb = new StringBuilder();
                    }
                    sb.append(s, runStart, pos);
                    pos++;
                    char e = peek();
                    pos++;
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw error("Truncated \\u escape");
                            }
                            try {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("Invalid \\u escape");
                            }
                            pos += 4;
                        }
                        default -> throw error("Invalid escape '\\" + e + "'");
                    }
                    runStart = pos;
                } else {
                    pos++;
                }
            }
        }

        void flatten(String prefix, FieldVisitor visitor, int depth) {
            if (depth > MAX_DEPTH) {
                throw error("Nested too deeply");
            }
            expect('{');
            ws();
            if (peek() == '}') {
                pos++;
                return;
            }
            while (true) {
                ws();
                int keyStart = pos;
                String key = string();
                String path = prefix.isEmpty() ? key : prefix + "." + key;
                ws();
                expect(':');
                ws();
                int valueStart = pos;
                char c = peek();
                if (c == '{') {
                    int before = pos;
                    flatten(path, visitor, depth + 1);
                    if (isEmptyObject(before)) {
                        visitor.field(path, "{}", keyStart, valueStart, pos);
                    }
                } else if (c == '"') {
                    String v = string();
                    visitor.field(path, v, keyStart, valueStart, pos);
                } else {
                    value(depth + 1);
                    visitor.field(path, s.substring(valueStart, pos), keyStart, valueStart, pos);
                }
                ws();
                char sep = peek();
                pos++;
                if (sep == '}') {
                    return;
                }
                if (sep != ',') {
                    pos--;
                    throw error("Expected ',' or '}'");
                }
            }
        }

        /// Whether the object starting at `open` contains only whitespace.
        private boolean isEmptyObject(int open) {
            int i = open + 1;
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            return i < s.length() && s.charAt(i) == '}';
        }
    }
}
