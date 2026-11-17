// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.io;

import com.sun.net.httpserver.HttpServer;
import io.github.logdelta.core.util.CancellationToken;
import io.github.logdelta.core.util.ProgressListener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogSourcesTest {

    @Test
    @DisplayName("downloads plain and gzip logs, sends headers and reports HTTP errors")
    void fetch() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/plain/app.log", ex -> {
            byte[] body = ("auth=" + ex.getRequestHeaders().getFirst("Authorization") + "\nline 2\n")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/gz/app.log.gz", ex -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
                gz.write("zipped\n".getBytes(StandardCharsets.UTF_8));
            }
            ex.sendResponseHeaders(200, bytes.size());
            ex.getResponseBody().write(bytes.toByteArray());
            ex.close();
        });
        server.createContext("/missing", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            LogSources.RemoteOptions opts = LogSources.RemoteOptions.DEFAULT.withHeader("Authorization", "Bearer t");

            LogFile plain = LogSources.load(base + "/plain/app.log", null, opts, ProgressListener.NONE,
                    CancellationToken.NEVER);
            assertEquals("app.log", plain.displayName());
            assertEquals(List.of("auth=Bearer t", "line 2"), plain.lines());

            LogFile gz = LogSources.fetch(URI.create(base + "/gz/app.log.gz"), null, opts, CancellationToken.NEVER);
            assertTrue(gz.compressed());
            assertEquals(List.of("zipped"), gz.lines());

            IOException e = assertThrows(IOException.class, () -> LogSources.fetch(
                    URI.create(base + "/missing?token=secret"), null, opts, CancellationToken.NEVER));
            assertTrue(e.getMessage().startsWith("HTTP 404"), e.getMessage());
            assertFalse(e.getMessage().contains("secret"), e.getMessage());

            LogSources.RemoteOptions tiny = new LogSources.RemoteOptions(java.util.Map.of(),
                    LogSources.DEFAULT_TIMEOUT, 4);
            assertThrows(IOException.class, () -> LogSources.fetch(URI.create(base + "/plain/app.log"), null, tiny,
                    CancellationToken.NEVER));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("recognises URLs, names and headers")
    void helpers() {
        assertTrue(LogSources.isUrl("HTTPS://example.com/x"));
        assertFalse(LogSources.isUrl("C:\\logs\\a.log"));
        assertEquals("example.com", LogSources.displayName(URI.create("https://example.com/")));
        assertEquals("b.log", LogSources.displayName(URI.create("https://example.com/a/b.log/")));
        assertEquals(List.of("X-Token", "a: b"), List.of(LogSources.parseHeader("X-Token:  a: b ")));
        assertThrows(IllegalArgumentException.class, () -> LogSources.parseHeader("nocolon"));
    }
}
