// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Deque;

/// A minimal streaming JSON writer with optional pretty printing.
///
/// The writer tracks nesting so that commas and indentation are emitted automatically:
///
/// ```java
/// JsonWriter w = new JsonWriter(out, true);
/// w.beginObject().name("lines").value(42).name("tags").beginArray().value("a").endArray().endObject();
/// ```
///
/// It intentionally supports only what LogDelta needs and performs no validation beyond basic
/// nesting checks.
public final class JsonWriter {

    private final Appendable out;
    private final boolean pretty;
    private final Deque<boolean[]> stack = new ArrayDeque<>(); // element: {isObject, hasEntries}
    private boolean afterName;

    /// @param out    destination
    /// @param pretty whether to indent with two spaces and put members on separate lines
    public JsonWriter(Appendable out, boolean pretty) {
        this.out = out;
        this.pretty = pretty;
    }

    /// Opens a JSON object.
    public JsonWriter beginObject() {
        beforeValue();
        write("{");
        stack.push(new boolean[] {true, false});
        return this;
    }

    /// Closes the current JSON object.
    public JsonWriter endObject() {
        return close(true, "}");
    }

    /// Opens a JSON array.
    public JsonWriter beginArray() {
        beforeValue();
        write("[");
        stack.push(new boolean[] {false, false});
        return this;
    }

    /// Closes the current JSON array.
    public JsonWriter endArray() {
        return close(false, "]");
    }

    /// Writes a member name; must be followed by exactly one value.
    public JsonWriter name(String name) {
        boolean[] top = stack.peek();
        if (top == null || !top[0] || afterName) {
            throw new IllegalStateException("name() is only valid directly inside an object");
        }
        separator(top);
        writeString(name);
        write(pretty ? ": " : ":");
        afterName = true;
        return this;
    }

    /// Writes a string value, or `null`.
    public JsonWriter value(String value) {
        beforeValue();
        if (value == null) {
            write("null");
        } else {
            writeString(value);
        }
        return this;
    }

    /// Writes a numeric value.
    public JsonWriter value(long value) {
        beforeValue();
        write(Long.toString(value));
        return this;
    }

    /// Writes a numeric value; non-finite numbers are written as `null`.
    public JsonWriter value(double value) {
        beforeValue();
        write(Double.isFinite(value) ? stripZeros(value) : "null");
        return this;
    }

    /// Writes a boolean value.
    public JsonWriter value(boolean value) {
        beforeValue();
        write(Boolean.toString(value));
        return this;
    }

    /// Writes `null`.
    public JsonWriter nullValue() {
        beforeValue();
        write("null");
        return this;
    }

    /// Ends the document with a trailing newline.
    public void finish() {
        if (!stack.isEmpty()) {
            throw new IllegalStateException("Unclosed JSON structure");
        }
        write("\n");
    }

    private JsonWriter close(boolean object, String token) {
        boolean[] top = stack.poll();
        if (top == null || top[0] != object || afterName) {
            throw new IllegalStateException("Mismatched " + token);
        }
        if (top[1] && pretty) {
            newline();
        }
        write(token);
        return this;
    }

    private void beforeValue() {
        if (afterName) {
            afterName = false;
            return;
        }
        boolean[] top = stack.peek();
        if (top != null) {
            if (top[0]) {
                throw new IllegalStateException("Object members need a name()");
            }
            separator(top);
        }
    }

    private void separator(boolean[] top) {
        if (top[1]) {
            write(",");
        }
        top[1] = true;
        if (pretty) {
            newline();
        }
    }

    private void newline() {
        write("\n");
        write("  ".repeat(stack.size()));
    }

    private static String stripZeros(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    private void writeString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        write(sb.toString());
    }

    private void write(String s) {
        try {
            out.append(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
