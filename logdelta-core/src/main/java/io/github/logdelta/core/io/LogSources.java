// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.io;

import io.github.logdelta.core.util.CancellationToken;
import io.github.logdelta.core.util.ProgressListener;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/// Opens a log from a *source specification*: either a local path or an `http(s)` URL.
///
/// Both front-ends accept URLs wherever they accept a file, which makes it possible to diff a
/// CI artifact or an object-store link without downloading it first. Downloads are read
/// straight into memory through [LogReader], so gzip and charset detection work the same way
/// as for local files.
///
/// Remote logs never get a [LogFile#path()]; their display name is the last path segment of
/// the URL (or the host name when the path is empty).
public final class LogSources {

    /// Default upper bound for a download, to keep a mistyped URL from filling the heap.
    public static final long DEFAULT_MAX_BYTES = 512L * 1024 * 1024;

    /// Default connect and response timeout.
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private LogSources() {
    }

    /// Settings for remote downloads.
    ///
    /// @param headers  extra request headers, e.g. `Authorization`; never `null`
    /// @param timeout  connect timeout and time allowed until the response headers arrive
    /// @param maxBytes largest accepted body in bytes (compressed size for gzip content)
    public record RemoteOptions(Map<String, String> headers, Duration timeout, long maxBytes) {

        /// No extra headers, [#DEFAULT_TIMEOUT] and [#DEFAULT_MAX_BYTES].
        public static final RemoteOptions DEFAULT = new RemoteOptions(Map.of(), DEFAULT_TIMEOUT, DEFAULT_MAX_BYTES);

        /// Validates the values and copies the header map.
        public RemoteOptions {
            headers = Map.copyOf(new LinkedHashMap<>(headers));
            Objects.requireNonNull(timeout, "timeout");
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("maxBytes must be positive");
            }
        }

        /// Returns a copy with one more header.
        public RemoteOptions withHeader(String name, String value) {
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put(name, value);
            return new RemoteOptions(h, timeout, maxBytes);
        }
    }

    /// Returns whether `spec` looks like an `http://` or `https://` URL.
    public static boolean isUrl(String spec) {
        String s = spec.strip().toLowerCase(Locale.ROOT);
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /// Parses a header given as `Name: value`, as used by the `--header` CLI option.
    ///
    /// @return a two-element array `{name, value}`
    /// @throws IllegalArgumentException if there is no colon or the name is blank
    public static String[] parseHeader(String header) {
        int colon = header.indexOf(':');
        if (colon <= 0 || header.substring(0, colon).isBlank()) {
            throw new IllegalArgumentException("Header must look like 'Name: value': " + header);
        }
        return new String[] {header.substring(0, colon).strip(), header.substring(colon + 1).strip()};
    }

    /// Loads a log from a path or URL.
    ///
    /// @param spec     local path or `http(s)` URL
    /// @param charset  charset to use, or `null` to detect it
    /// @param remote   download settings, ignored for local files
    /// @param progress receives read progress for local files
    /// @param token    allows the caller to abort
    public static LogFile load(String spec, Charset charset, RemoteOptions remote, ProgressListener progress,
                               CancellationToken token) throws IOException {
        if (isUrl(spec)) {
            return fetch(toUri(spec), charset, remote, token);
        }
        return LogReader.read(Path.of(spec), charset, progress, token);
    }

    /// Downloads a log over HTTP(S).
    ///
    /// Redirects are followed (except from HTTPS to HTTP). Any status other than 2xx is
    /// reported as an [IOException] that names the status code and the URL.
    public static LogFile fetch(URI uri, Charset charset, RemoteOptions options, CancellationToken token)
            throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IOException("Only http and https URLs are supported: " + uri);
        }
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(options.timeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            return fetch(client, uri, charset, options, token);
        }
    }

    private static LogFile fetch(HttpClient client, URI uri, Charset charset, RemoteOptions options,
                                 CancellationToken token) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(options.timeout())
                .header("Accept", "text/plain, application/gzip, */*;q=0.5")
                .GET();
        options.headers().forEach(request::header);
        HttpResponse<InputStream> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Download interrupted: " + uri);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid request for " + redact(uri) + ": " + e.getMessage(), e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException("Timed out downloading " + redact(uri), e);
        } catch (java.net.ConnectException e) {
            throw new IOException("Cannot connect to " + redact(uri)
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
        } catch (IOException e) {
            if (e.getMessage() != null) {
                throw e;
            }
            throw new IOException("Download failed for " + redact(uri) + " (" + e.getClass().getSimpleName() + ")", e);
        }
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status < 200 || status > 299) {
                throw new IOException("HTTP " + status + " from " + redact(uri));
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > options.maxBytes()) {
                throw new IOException("Remote log is larger than the limit of " + options.maxBytes() + " bytes: "
                        + redact(uri));
            }
            InputStream limited = new LimitedInputStream(body, options.maxBytes(), uri, token);
            return LogReader.read(displayName(uri), limited, charset);
        }
    }

    /// Converts a URL string to a [URI], reporting syntax errors as [IOException].
    public static URI toUri(String spec) throws IOException {
        try {
            return new URI(spec.strip());
        } catch (URISyntaxException e) {
            throw new IOException("Not a valid URL: " + spec, e);
        }
    }

    /// Display name for a URL: the last non-empty path segment, or the host.
    public static String displayName(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        String trimmed = path.substring(0, end);
        int slash = trimmed.lastIndexOf('/');
        String last = trimmed.substring(slash + 1);
        return last.isEmpty() ? (uri.getHost() == null ? uri.toString() : uri.getHost()) : last;
    }

    /// Removes user info and the query string, which often carry credentials, for messages.
    static String redact(URI uri) {
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://").append(uri.getHost() == null ? "" : uri.getHost());
        if (uri.getPort() >= 0) {
            sb.append(':').append(uri.getPort());
        }
        sb.append(uri.getRawPath() == null ? "" : uri.getRawPath());
        if (uri.getRawQuery() != null) {
            sb.append("?…");
        }
        return sb.toString();
    }

    /// Fails once more than `limit` bytes have been read and checks for cancellation.
    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private final URI uri;
        private final CancellationToken token;
        private long count;

        LimitedInputStream(InputStream in, long limit, URI uri, CancellationToken token) {
            super(in);
            this.limit = limit;
            this.uri = uri;
            this.token = token;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                advance(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                advance(n);
            }
            return n;
        }

        private void advance(long n) throws IOException {
            count += n;
            token.throwIfCancelled();
            if (count > limit) {
                throw new IOException("Remote log is larger than the limit of " + limit + " bytes: " + redact(uri));
            }
        }
    }
}
