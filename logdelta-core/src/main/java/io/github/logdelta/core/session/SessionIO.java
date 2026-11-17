// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.session;

import io.github.logdelta.core.Version;
import io.github.logdelta.core.config.ProfileIO;
import io.github.logdelta.core.config.ProfileParseException;
import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.io.LogSources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// Reads and writes session files (`*.ldsession`).
///
/// A session file is a [profile][ProfileIO] with two extra kinds of section, so everything a
/// profile can express (options, ignore patterns, custom rules) is saved with the session:
///
/// ```
/// # LogDelta session
/// [session]
/// left = logs/good.log
/// right = https://ci.example.com/job/812/artifact/app.log
/// # repeatable: more known-good runs
/// baseline = logs/good-2.log
/// notes = "Started after the pool resize.\nSee ticket OPS-311."
///
/// [bookmark]
/// side = right
/// line = 57
/// text = "2026-11-02 10:14:07.902 ERROR [pool-3] Connection refused"
/// note = "first sign of the outage"
///
/// [options]
/// algorithm = patience
/// ...
/// ```
///
/// Local paths inside the session file's folder are stored relative to it, so a folder with
/// logs and a session can be zipped and shared. Values are written in double quotes with
/// `\n`, `\t`, `\"` and `\\` escapes; unquoted values are read verbatim (stripped).
public final class SessionIO {

    /// File extension without the dot.
    public static final String EXTENSION = "ldsession";

    private SessionIO() {
    }

    /// Reads a session file. Relative local sources are resolved against the file's folder.
    public static Session read(Path file) throws IOException, SessionParseException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Path base = file.toAbsolutePath().getParent();
        return parse(text, base);
    }

    /// Parses session text.
    ///
    /// @param text    file content
    /// @param baseDir folder that relative local sources are resolved against, or `null`
    ///                to keep them as written
    public static Session parse(String text, Path baseDir) throws SessionParseException {
        String[] lines = text.split("\\r?\\n|\\r", -1);
        StringBuilder profile = new StringBuilder();
        String left = null;
        String right = null;
        List<String> baselines = new ArrayList<>();
        String notes = "";
        List<Bookmark> bookmarks = new ArrayList<>();
        BookmarkDraft draft = null;
        String section = null;
        boolean sawSession = false;

        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String raw = lines[i];
            String line = raw.strip();
            if (line.startsWith("[") && line.endsWith("]")) {
                if (draft != null) {
                    bookmarks.add(draft.build());
                    draft = null;
                }
                String header = line.substring(1, line.length() - 1).strip().toLowerCase(Locale.ROOT);
                switch (header) {
                    case "session" -> {
                        if (sawSession) {
                            throw new SessionParseException(lineNo, "Duplicate [session] section", null);
                        }
                        sawSession = true;
                        section = "session";
                    }
                    case "bookmark" -> {
                        section = "bookmark";
                        draft = new BookmarkDraft(lineNo);
                    }
                    default -> section = null;
                }
                profile.append(section == null ? raw : "").append('\n');
                continue;
            }
            if (section == null || line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                profile.append(section == null ? raw : "").append('\n');
                continue;
            }
            profile.append('\n');
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new SessionParseException(lineNo, "Expected 'key = value'", null);
            }
            String key = line.substring(0, eq).strip().toLowerCase(Locale.ROOT);
            String value = unescape(line.substring(eq + 1).strip(), lineNo);
            if (section.equals("session")) {
                switch (key) {
                    case "left" -> left = resolve(value, baseDir);
                    case "right" -> right = resolve(value, baseDir);
                    case "baseline" -> baselines.add(resolve(value, baseDir));
                    case "notes" -> notes = value;
                    case "version" -> {
                        // informational
                    }
                    default -> throw new SessionParseException(lineNo, "Unknown session setting '" + key + "'", null);
                }
            } else {
                draft.set(key, value, lineNo);
            }
        }
        if (draft != null) {
            bookmarks.add(draft.build());
        }
        if (!sawSession) {
            throw new SessionParseException(0, "Not a session file: no [session] section", null);
        }
        if (left == null || right == null) {
            throw new SessionParseException(0, "The [session] section needs both 'left' and 'right'", null);
        }
        DiffOptions options;
        try {
            options = ProfileIO.parse(profile.toString());
        } catch (ProfileParseException e) {
            throw new SessionParseException(e.line(), stripLinePrefix(e.getMessage()), e);
        }
        return new Session(left, right, baselines, options, bookmarks, notes);
    }

    /// Serializes a session. Local sources inside `baseDir` are written relative to it.
    ///
    /// @param baseDir folder the session file will live in, or `null` to keep paths as given
    public static String format(Session s, Path baseDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(Version.NAME).append(" session (").append(Version.VERSION).append(")\n");
        sb.append("# Format reference: docs/SESSIONS.md\n\n");
        sb.append("[session]\n");
        sb.append("left = ").append(escape(relativize(s.left(), baseDir))).append('\n');
        sb.append("right = ").append(escape(relativize(s.right(), baseDir))).append('\n');
        for (String b : s.extraBaselines()) {
            sb.append("baseline = ").append(escape(relativize(b, baseDir))).append('\n');
        }
        if (!s.notes().isEmpty()) {
            sb.append("notes = ").append(escape(s.notes())).append('\n');
        }
        for (Bookmark b : s.bookmarks()) {
            sb.append("\n[bookmark]\n");
            sb.append("side = ").append(b.side().key()).append('\n');
            sb.append("line = ").append(b.line()).append('\n');
            if (!b.text().isEmpty()) {
                sb.append("text = ").append(escape(b.text())).append('\n');
            }
            if (!b.note().isEmpty()) {
                sb.append("note = ").append(escape(b.note())).append('\n');
            }
        }
        sb.append('\n');
        String profile = ProfileIO.format(s.options());
        // Drop the profile's own header comment; the session has one already.
        int start = profile.indexOf("[options]");
        sb.append(start >= 0 ? profile.substring(start) : profile);
        return sb.toString();
    }

    /// Writes a session file (UTF-8), storing local paths relative to the file's folder.
    public static void write(Session session, Path file) throws IOException {
        Path base = file.toAbsolutePath().getParent();
        if (base != null) {
            Files.createDirectories(base);
        }
        Files.writeString(file, format(session, base), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------

    private static String resolve(String source, Path baseDir) {
        if (baseDir == null || LogSources.isUrl(source)) {
            return source;
        }
        try {
            Path p = Path.of(source);
            return p.isAbsolute() ? source : baseDir.resolve(p).normalize().toString();
        } catch (InvalidPathException _) {
            return source;
        }
    }

    private static String relativize(String source, Path baseDir) {
        if (baseDir == null || LogSources.isUrl(source)) {
            return source;
        }
        try {
            Path p = Path.of(source).toAbsolutePath().normalize();
            Path base = baseDir.toAbsolutePath().normalize();
            if (p.startsWith(base)) {
                return base.relativize(p).toString().replace('\\', '/');
            }
            return source;
        } catch (InvalidPathException _) {
            return source;
        }
    }

    static String escape(String v) {
        StringBuilder sb = new StringBuilder(v.length() + 2).append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    static String unescape(String v, int line) throws SessionParseException {
        if (!v.startsWith("\"")) {
            return v;
        }
        if (v.length() < 2 || !v.endsWith("\"") || isEscapedQuote(v, v.length() - 1)) {
            throw new SessionParseException(line, "Unterminated quoted value", null);
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 1; i < v.length() - 1; i++) {
            char c = v.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (++i >= v.length() - 1) {
                throw new SessionParseException(line, "Dangling backslash", null);
            }
            char e = v.charAt(i);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case '"', '\\' -> sb.append(e);
                default -> throw new SessionParseException(line, "Unknown escape \\" + e, null);
            }
        }
        return sb.toString();
    }

    /// Whether the quote at `index` is preceded by an odd number of backslashes.
    private static boolean isEscapedQuote(String v, int index) {
        int n = 0;
        for (int i = index - 1; i >= 1 && v.charAt(i) == '\\'; i--) {
            n++;
        }
        return n % 2 == 1;
    }

    private static String stripLinePrefix(String message) {
        return message.replaceFirst("^line \\d+: ", "");
    }

    /// Collects the settings of one `[bookmark]` section.
    private static final class BookmarkDraft {
        private final int headerLine;
        private Bookmark.Side side;
        private int line;
        private String text = "";
        private String note = "";

        BookmarkDraft(int headerLine) {
            this.headerLine = headerLine;
        }

        void set(String key, String value, int lineNo) throws SessionParseException {
            switch (key) {
                case "side" -> {
                    try {
                        side = Bookmark.Side.parse(value);
                    } catch (IllegalArgumentException e) {
                        throw new SessionParseException(lineNo, e.getMessage(), null);
                    }
                }
                case "line" -> {
                    try {
                        line = Integer.parseInt(value);
                    } catch (NumberFormatException _) {
                        throw new SessionParseException(lineNo, "Not a line number: " + value, null);
                    }
                    if (line < 1) {
                        throw new SessionParseException(lineNo, "Line numbers start at 1", null);
                    }
                }
                case "text" -> text = value;
                case "note" -> note = value;
                default -> throw new SessionParseException(lineNo, "Unknown bookmark setting '" + key + "'", null);
            }
        }

        Bookmark build() throws SessionParseException {
            if (side == null || line == 0) {
                throw new SessionParseException(headerLine, "A bookmark needs 'side' and 'line'", null);
            }
            return new Bookmark(side, line, text, note);
        }
    }
}
