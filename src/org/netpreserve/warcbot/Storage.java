package org.netpreserve.warcbot;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.netpreserve.jwarc.*;
import org.openqa.selenium.remote.http.Contents;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

import java.io.Closeable;
import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

public class Storage implements Closeable {
    private final WarcWriter warcWriter;
    final StorageDAO dao;
    private final TimeBasedEpochGenerator uuidGenerator;

    public Storage(Path directory, StorageDAO dao) throws IOException {
        warcWriter = new WarcWriter(directory.resolve("test.warc"));
        this.dao = dao;
        this.uuidGenerator = Generators.timeBasedEpochGenerator();
    }

    @Override
    public void close() throws IOException {
        warcWriter.close();
    }

    private WarcDigest sha1(byte[] data) {
        try {
            var digest = MessageDigest.getInstance("SHA-1");
            digest.update(data);
            return new WarcDigest(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private WarcDigest sha1(Contents.Supplier content) throws IOException {
        try (var stream = content.get()) {
            var digest = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[8192];
            while (true) {
                int n = stream.read(buf);
                if (n == -1) break;
                digest.update(buf, 0, n);
            }
            return new WarcDigest(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }

    }

    public void save(UUID pageId, HttpRequest request, HttpResponse response) throws IOException, SQLException {
        try (var responseStream = response.getContent().get();
             var requestStream = request.getContent().get()) {

            var httpResponseBuilder = new org.netpreserve.jwarc.HttpResponse.Builder(response.getStatus(), "")
                    .body(null, Channels.newChannel(responseStream), response.getContent().length());
            response.forEachHeader((name, value) -> {
                if (name.equalsIgnoreCase("content-length")) return;
                if (name.equalsIgnoreCase("content-encoding")) return;
                httpResponseBuilder.addHeader(name, value);
            });
            WarcDigest responsePayload = sha1(response.getContent());

            var httpRequestBuilder = new org.netpreserve.jwarc.HttpRequest.Builder(request.getMethod().name(), request.getUri());
            request.forEachHeader((name, value) -> {
                if (name.equalsIgnoreCase("content-length")) return;
                if (name.equalsIgnoreCase("content-encoding")) return;
                httpRequestBuilder.addHeader(name, value);
            });

            WarcDigest requestPayload = null;
            if (request.getContent().length() > 0) {
                httpRequestBuilder.body(null, Channels.newChannel(requestStream), request.getContent().length());
                requestPayload = sha1(request.getContent());
            }

            save(pageId, request.getUri(), httpRequestBuilder.build(), httpResponseBuilder.build(),
                    requestPayload, responsePayload);
        }
    }

    /**
     * Stores an HTTP response chain.
     *
     * <p>This method processes a chain of HTTP responses (where each response may have a reference
     * to the previous response in the chain) and saves each response along with its associated request.
     * </p>
     */
    public void save(UUID pageId, java.net.http.HttpResponse<byte[]> responseChain) throws SQLException, IOException {
        // walk the response chain backwards and then reverse it so the responses are in order they were made
        var responses = new ArrayList<java.net.http.HttpResponse<byte[]>>();
        for (var response = responseChain; response != null; response = response.previousResponse().orElse(null)) {
            responses.add(response);
        }
        Collections.reverse(responses);

        for (var response : responses) {
            var request = response.request();
            var httpResponse = new org.netpreserve.jwarc.HttpResponse.Builder(response.statusCode(), "")
                    .addHeaders(stripHttp2Headers(response.headers()))
                    .body(null, response.body())
                    .build();

            var httpRequest = new org.netpreserve.jwarc.HttpRequest.Builder(request.method(), request.uri().getRawPath())
                    .addHeaders(stripHttp2Headers(request.headers()))
                    .build();
            save(pageId, request.uri().toString(), httpRequest, httpResponse, null, sha1(response.body()));
        }
    }

    private Map<String, List<String>> stripHttp2Headers(HttpHeaders headers) {
        var map = new LinkedHashMap<String, List<String>>();
        headers.map().forEach((name, values) -> {
            if (name.startsWith(":")) return;
            map.put(name, values);
        });
        return map;
    }

    private void save(@NotNull UUID pageId,
                      @NotNull String uri,
                      @NotNull org.netpreserve.jwarc.HttpRequest httpRequest,
                      @NotNull org.netpreserve.jwarc.HttpResponse httpResponse,
                      @Nullable WarcDigest requestDigest,
                      @Nullable WarcDigest responseDigest) throws IOException, SQLException {
        Instant now = Instant.now();
        UUID responseUuid = uuidGenerator.construct(now.toEpochMilli());
        var warcResponseBuilder = new WarcResponse.Builder(uri)
                .date(now)
                .recordId(responseUuid)
                .body(httpResponse);
        if (responseDigest != null) warcResponseBuilder.payloadDigest(responseDigest);
        WarcResponse warcResponse = warcResponseBuilder.build();

        var warcRequestBuilder = new WarcRequest.Builder(uri)
                .date(now)
                .recordId(uuidGenerator.construct(now.toEpochMilli()))
                .concurrentTo(warcResponse.id())
                .body(httpRequest);
        if (requestDigest != null) warcRequestBuilder.payloadDigest(requestDigest);
        WarcRequest warcRequest = warcRequestBuilder.build();

        long responseOffset;
        long responseLength;
        long requestLength;

        synchronized (warcWriter) {
            responseOffset = warcWriter.position();
            warcWriter.write(warcResponse);
            responseLength = warcWriter.position() - responseOffset;
            warcWriter.write(warcRequest);
            requestLength = warcWriter.position() - responseOffset - responseLength;
        }

        dao.addResource(new Resource(responseUuid, pageId,
                warcResponse.target(), warcResponse.date(), responseOffset, responseLength, requestLength,
                httpResponse.status(), httpResponse.headers().first("Location").orElse(null),
                httpResponse.contentType().base().toString(),
                httpResponse.body().size(),
                responseDigest));
    }
}
