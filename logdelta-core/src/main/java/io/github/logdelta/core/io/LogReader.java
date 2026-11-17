// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.io;

import io.github.logdelta.core.util.CancellationToken;
import io.github.logdelta.core.util.ProgressListener;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
public final class LogReader {

    private static final int SNIFF_BYTES = 64 * 1024;
    private static final int PROGRESS_EVERY_LINES = 4096;

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
        long size = Files.size(path);
        try (InputStream raw = Files.newInputStream(path)) {
            CountingInputStream counting = new CountingInputStream(raw);
            String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
            ProgressListener scaled = size <= 0 ? ProgressListener.NONE
                    : (stage, _) -> progress.onProgress(stage, Math.min(1.0, counting.count / (double) size));
            Decoded decoded = decode(counting, charset, scaled, token);
            return new LogFile(name, path, decoded.lines, decoded.charset, decoded.compressed, size);
        }
    }

    /// Reads a log from a stream. The stream is not closed.
    ///
    /// @param displayName name to use for the resulting [LogFile]
    /// @param in          source stream
    /// @param charset     charset to use, or `null` to detect it
    public static LogFile read(String displayName, InputStream in, Charset charset) throws IOException {
        CountingInputStream counting = new CountingInputStream(in);
        Decoded decoded = decode(counting, charset, ProgressListener.NONE, CancellationToken.NEVER);
        return new LogFile(displayName, null, decoded.lines, decoded.charset, decoded.compressed, counting.count);
    }

    private record Decoded(List<String> lines, Charset charset, boolean compressed) {
    }

    private static Decoded decode(InputStream source, Charset forced, ProgressListener progress,
                                  CancellationToken token) throws IOException {
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
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, decoder), 64 * 1024)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                if (lines.size() % PROGRESS_EVERY_LINES == 0) {
                    token.throwIfCancelled();
                    progress.onProgress("Reading", 0);
                }
            }
        }
        progress.onProgress("Reading", 1);
        return new Decoded(lines, charset, gzip);
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
