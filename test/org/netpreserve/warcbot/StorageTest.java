package org.netpreserve.warcbot;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class StorageTest {

    @Test
    public void test(@TempDir Path tempDir) throws IOException, SQLException {
        try (var storage = new Storage(tempDir, new StorageDB() {
            @Override
            public void insertResource(@NotNull UUID id, @NotNull UUID pageId, @NotNull String url, @NotNull Instant date, long responseOffset, long responseLength, long requestLength, int status, @Nullable String redirect, String payloadType, long payloadSize) throws SQLException {

            }
        })) {
            var response = new HttpResponse<String>() {

                @Override
                public int statusCode() {
                    return 200;
                }

                @Override
                public HttpRequest request() {
                    return HttpRequest.newBuilder(URI.create("https://example.com/")).GET().build();
                }

                @Override
                public Optional<HttpResponse<String>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(Map.of("content-length", List.of("5")), (name, value) -> true);
                }

                @Override
                public String body() {
                    return "hello";
                }

                @Override
                public Optional<SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public URI uri() {
                    return URI.create("http://example.com/");
                }

                @Override
                public HttpClient.Version version() {
                    return null;
                }
            };
            storage.save(UUID.randomUUID(), response);
        }
    }

}