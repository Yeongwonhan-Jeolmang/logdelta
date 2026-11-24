// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.io;

import io.github.logdelta.core.timing.TimeWindow;
import io.github.logdelta.core.util.CancellationToken;
import io.github.logdelta.core.util.ProgressListener;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/// Reads log files line by line with automatic gzip and charset detection.
///
/// Detection rules:
///
/// - **Compression** — content that starts with the gzip magic bytes is decompressed
///   transparently, regardless of the file extension.
/// - **Charset** — a byte order mark selects UTF-8 or UTF-16. Otherwise the first
///   64 KiB are test-decoded as UTF-8; if that fails the file is read as ISO-8859-1,
///   which never fails and preserves every byte.
///
/// Lines are split on `\n`, `\r\n` and `\r`. The reader streams the input,
/// so memory use is proportional to the decoded text, not to the compressed size.
///
/// ## Large files
///
/// Uncompressed files of at least [#MMAP_THRESHOLD] bytes in UTF-8 or Latin-1 are read through
/// memory-mapped buffers, which avoids copying the file through a stream decoder. With a
/// [TimeWindow], lines before the window are skipped and reading stops as soon as the window has
/// ended, so only the part of a multi-gigabyte log you care about is held in memory.
public final class LogReader {

    /// Files at least this large (and not compressed) are memory-mapped.
    public static final long MMAP_THRESHOLD = 32L * 1024 * 1024;

    private static final int SNIFF_BYTES = 64 * 1024;
    private static final int PROGRESS_EVERY_LINES = 4096;
    private static final long MAP_CHUNK = 256L * 1024 * 1024;
    /// The thresholds in effect; tests lower them to exercise the memory-mapped path.
    static long mmapThreshold = MMAP_THRESHOLD;
    static long mapChunk = MAP_CHUNK;

    private LogReader() {
    }

    /// Reads a file with automatic charset detection.
    public static LogFile read(Path path) throws IOException {
        return read(path, null, ProgressListener.NONE, CancellationToken.NEVER);
    }

    /// Reads a file.
    ///
    /// @param path     file to read
    /// @param charset  charset to use, or `null` to detect it
    /// @param progress receives the fraction of bytes consumed
    /// @param token    allows the caller to abort the read
    public static LogFile read(Path path, Charset charset, ProgressListener progress,
                               CancellationToken token) throws IOException {
        return read(path, charset, progress, token, null);
    }

    /// Reads the part of a file that falls inside a time window.
    ///
    /// @param window lines to keep, or `null` for all of them
    public static LogFile read(Path path, Charset charset, ProgressListener progress,
                               CancellationToken token, TimeWindow window) throws IOException {
        long size = Files.size(path);
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        Sink sink = new Sink(window);
        if (size >= mmapThreshold) {
            Decoded mapped = readMapped(path, size, charset, sink, progress, token);
            if (mapped != null) {
                return new LogFile(name, path, sink.lines, mapped.charset, false, size, sink.firstLineNumber(), window);
            }
        }
        try (InputStream raw = Files.newInputStream(path)) {
            CountingInputStream counting = new CountingInputStream(raw);
            ProgressListener scaled = size <= 0 ? ProgressListener.NONE
                    : (stage, _) -> progress.onProgress(stage, Math.min(1.0, counting.count / (double) size));
            Decoded decoded = decode(counting, charset, scaled, token, sink);
            return new LogFile(name, path, sink.lines, decoded.charset, decoded.compressed, size,
                    sink.firstLineNumber(), window);
        }
    }

    /// Reads a log from a stream. The stream is not closed.
    ///
    /// @param displayName name to use for the resulting [LogFile]
    /// @param in          source stream
    /// @param charset     charset to use, or `null` to detect it
    public static LogFile read(String displayName, InputStream in, Charset charset) throws IOException {
        CountingInputStream counting = new CountingInputStream(in);
        Sink sink = new Sink(null);
        Decoded decoded = decode(counting, charset, ProgressListener.NONE, CancellationToken.NEVER, sink);
        return new LogFile(displayName, null, sink.lines, decoded.charset, decoded.compressed, counting.count);
    }

    private record Decoded(Charset charset, boolean compressed) {
    }

    /// Collects lines, applying the time window if there is one.
    private static final class Sink {
        final List<String> lines = new ArrayList<>();
        private final TimeWindow.Slicer slicer;
        private int seen;
        private int first = -1;

        Sink(TimeWindow window) {
            this.slicer = window == null ? null : window.slicer();
        }

        /// Adds a line; returns `false` when reading can stop.
        boolean add(String line) {
            int index = seen++;
            if (slicer != null) {
                TimeWindow.Slicer.Decision d = slicer.accept(line);
                if (d == TimeWindow.Slicer.Decision.STOP) {
                    return false;
                }
                if (d == TimeWindow.Slicer.Decision.SKIP) {
                    return true;
                }
            }
            if (first < 0) {
                first = index;
            }
            lines.add(line);
            return true;
        }

        int firstLineNumber() {
            return first < 0 ? seen + 1 : first + 1;
        }
    }

    private static Decoded decode(InputStream source, Charset forced, ProgressListener progress,
                                  CancellationToken token, Sink sink) throws IOException {
        BufferedInputStream in = new BufferedInputStream(source, SNIFF_BYTES);
        boolean gzip = startsWith(in, new byte[] {(byte) 0x1f, (byte) 0x8b});
        if (gzip) {
            in = new BufferedInputStream(new GZIPInputStream(in, 64 * 1024), SNIFF_BYTES);
        }

        Charset charset = forced;
        int bomLength = 0;
        if (charset == null) {
            in.mark(SNIFF_BYTES);
            byte[] sample = in.readNBytes(SNIFF_BYTES);
            in.reset();
            Bom bom = Bom.detect(sample);
            if (bom != null) {
                charset = bom.charset;
                bomLength = bom.length;
            } else {
                charset = looksLikeUtf8(sample, sample.length == SNIFF_BYTES)
                        ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
            }
        } else {
            in.mark(4);
            Bom bom = Bom.detect(in.readNBytes(4));
            in.reset();
            if (bom != null && bom.charset.equals(charset)) {
                bomLength = bom.length;
            }
        }
        in.skipNBytes(bomLength);

        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, decoder), 64 * 1024)) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                if (!sink.add(line)) {
                    break;
                }
                if (++count % PROGRESS_EVERY_LINES == 0) {
                    token.throwIfCancelled();
                    progress.onProgress("Reading", 0);
                }
            }
        }
        progress.onProgress("Reading", 1);
        return new Decoded(charset, gzip);
    }

    /// Reads a large uncompressed file through memory-mapped chunks.
    ///
    /// @return `null` if the file is compressed or its charset is not byte-oriented (UTF-8,
    ///         Latin-1, ASCII); the caller then falls back to the streaming reader
    private static Decoded readMapped(Path path, long size, Charset forced, Sink sink, ProgressListener progress,
                                      CancellationToken token) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer headBuf = ByteBuffer.allocate((int) Math.min(SNIFF_BYTES, size));
            while (headBuf.hasRemaining() && ch.read(headBuf, headBuf.position()) > 0) {
                // fill the sample
            }
            byte[] head = java.util.Arrays.copyOf(headBuf.array(), headBuf.position());
            if (head.length >= 2 && (head[0] & 0xFF) == 0x1f && (head[1] & 0xFF) == 0x8b) {
                return null;
            }
            Bom bom = Bom.detect(head);
            Charset charset = forced;
            if (charset == null) {
                charset = bom != null ? bom.charset
                        : looksLikeUtf8(head, head.length == SNIFF_BYTES) ? StandardCharsets.UTF_8
                        : StandardCharsets.ISO_8859_1;
            }
            if (!isByteOriented(charset)) {
                return null;
            }
            long pos = bom != null && bom.charset.equals(charset) ? bom.length : 0;
            byte[] carry = new byte[0];
            int carryLen = 0;
            byte[] lineBuf = new byte[8192];
            boolean pendingCr = false;
            int count = 0;
            outer:
            while (pos < size) {
                long len = Math.min(mapChunk, size - pos);
                MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, pos, len);
                int limit = (int) len;
                int start = 0;
                for (int i = 0; i < limit; i++) {
                    byte b = buf.get(i);
                    if (b != '\n' && b != '\r') {
                        continue;
                    }
                    if (b == '\n' && pendingCr && i == start && carryLen == 0) {
                        // second half of a \r\n that straddled the previous terminator
                        pendingCr = false;
                        start = i + 1;
                        continue;
                    }
                    pendingCr = false;
                    int partLen = i - start;
                    int total = carryLen + partLen;
                    if (lineBuf.length < total) {
                        lineBuf = new byte[Math.max(total, lineBuf.length * 2)];
                    }
                    System.arraycopy(carry, 0, lineBuf, 0, carryLen);
                    buf.get(start, lineBuf, carryLen, partLen);
                    carryLen = 0;
                    if (!sink.add(new String(lineBuf, 0, total, charset))) {
                        break outer;
                    }
                    if (b == '\r') {
                        if (i + 1 < limit) {
                            if (buf.get(i + 1) == '\n') {
                                i++;
                            }
                        } else {
                            pendingCr = true;
                        }
                    }
                    start = i + 1;
                    if (++count % PROGRESS_EVERY_LINES == 0) {
                        token.throwIfCancelled();
                        progress.onProgress("Reading", Math.min(1.0, (pos + i) / (double) size));
                    }
                }
                int rest = limit - start;
                if (rest > 0) {
                    if (carry.length < carryLen + rest) {
                        carry = java.util.Arrays.copyOf(carry, Math.max(carryLen + rest, carry.length * 2));
                    }
                    buf.get(start, carry, carryLen, rest);
                    carryLen += rest;
                    pendingCr = false;
                }
                pos += len;
            }
            if (carryLen > 0) {
                sink.add(new String(carry, 0, carryLen, charset));
            }
            progress.onProgress("Reading", 1);
            return new Decoded(charset, false);
        }
    }

    private static boolean isByteOriented(Charset c) {
        return c.equals(StandardCharsets.UTF_8) || c.equals(StandardCharsets.ISO_8859_1)
                || c.equals(StandardCharsets.US_ASCII);
    }

    private static boolean startsWith(BufferedInputStream in, byte[] magic) throws IOException {
        in.mark(magic.length);
        byte[] head = in.readNBytes(magic.length);
        in.reset();
        if (head.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (head[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /// Returns whether `sample` decodes as UTF-8. When the sample was cut at the sniff limit,
    /// up to three trailing bytes of an incomplete multi-byte sequence are ignored.
    static boolean looksLikeUtf8(byte[] sample, boolean truncated) {
        int len = sample.length;
        if (truncated) {
            int back = 0;
            while (back < 3 && len - back - 1 >= 0 && (sample[len - back - 1] & 0xC0) == 0x80) {
                back++;
            }
            if (len - back - 1 >= 0 && (sample[len - back - 1] & 0xC0) == 0xC0) {
                len = len - back - 1;
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(sample, 0, len));
            return true;
        } catch (CharacterCodingException _) {
            return false;
        }
    }

    private record Bom(Charset charset, int length) {
        static Bom detect(byte[] b) {
            if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
                return new Bom(StandardCharsets.UTF_8, 3);
            }
            if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
                return new Bom(StandardCharsets.UTF_16LE, 2);
            }
            if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
                return new Bom(StandardCharsets.UTF_16BE, 2);
            }
            return null;
        }
    }

    /// Counts bytes read so that progress can be reported against the file size.
    private static final class CountingInputStream extends FilterInputStream {
        private volatile long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(n);
            count += s;
            return s;
        }
    }
}
