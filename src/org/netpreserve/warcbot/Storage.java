package org.netpreserve.warcbot;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.netpreserve.jwarc.*;
import org.openqa.selenium.remote.http.Contents;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static java.nio.file.StandardOpenOption.*;

public class Storage implements Closeable {
    private final Logger log = LoggerFactory.getLogger(Storage.class);
    private final WarcWriter warcWriter;
    final StorageDAO dao;
    private final TimeBasedEpochGenerator uuidGenerator;
    private final static DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private final SecureRandom random = new SecureRandom();

    public Storage(Path directory, StorageDAO dao) throws IOException {
        String basename = "warcbot-" + DATE_FORMAT.format(Instant.now()) + "-" + randomId();
        warcWriter = new WarcWriter(FileChannel.open(directory.resolve(basename + ".warc.gz"),
                WRITE, CREATE, TRUNCATE_EXISTING), WarcCompression.GZIP);
        this.dao = dao;
        this.uuidGenerator = Generators.timeBasedEpochGenerator();
    }

    private String randomId() {
        String alphabet = "ABCDFGHJKLMNPQRSTVWXYZabcdfghjklmnpqrstvwxyz0123456789";
        var sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
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

    public Resource save(UUID pageId, HttpRequest request, HttpResponse response) throws IOException {
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

            return save(pageId, request.getUri(), httpRequestBuilder.build(), httpResponseBuilder.build(),
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
    public List<Resource> save(UUID pageId, java.net.http.HttpResponse<byte[]> responseChain) throws IOException {
        // walk the response chain backwards and then reverse it so the responses are in order they were made
        var responses = new ArrayList<java.net.http.HttpResponse<byte[]>>();
        for (var response = responseChain; response != null; response = response.previousResponse().orElse(null)) {
            responses.add(response);
        }
        Collections.reverse(responses);

        List<Resource> resources = new ArrayList<>();
        for (var response : responses) {
            var request = response.request();
            var httpResponse = new org.netpreserve.jwarc.HttpResponse.Builder(response.statusCode(), "")
                    .addHeaders(stripHttp2Headers(response.headers()))
                    .body(null, response.body())
                    .build();

            var httpRequest = new org.netpreserve.jwarc.HttpRequest.Builder(request.method(), request.uri().getRawPath())
                    .addHeaders(stripHttp2Headers(request.headers()))
                    .build();
            Resource resource = save(pageId, request.uri().toString(), httpRequest, httpResponse, null, sha1(response.body()));
            if (resource != null) resources.add(resource);
        }
        return resources;
    }

    private Map<String, List<String>> stripHttp2Headers(HttpHeaders headers) {
        var map = new LinkedHashMap<String, List<String>>();
        headers.map().forEach((name, values) -> {
            if (name.startsWith(":")) return;
            map.put(name, values);
        });
        return map;
    }

    private Resource save(@NotNull UUID pageId,
                          @NotNull String uri,
                          @NotNull org.netpreserve.jwarc.HttpRequest httpRequest,
                          @NotNull org.netpreserve.jwarc.HttpResponse httpResponse,
                          @Nullable WarcDigest requestDigest,
                          @Nullable WarcDigest responseDigest) throws IOException {

        var existingDuplicate = dao.findResourceByUrlAndPayload(uri,
                httpResponse.body().size(),
                responseDigest == null ? null : responseDigest.prefixedBase32());
        if (existingDuplicate != null) {
            log.debug("Not saving duplicate of resource {}: {}", existingDuplicate.id(), uri);
            return null;
        }

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

        Resource resource = new Resource(responseUuid, pageId,
                warcResponse.target(), warcResponse.date(), responseOffset, responseLength, requestLength,
                httpResponse.status(), httpResponse.headers().first("Location").orElse(null),
                httpResponse.contentType().base().toString(),
                httpResponse.body().size(),
                responseDigest);
        dao.addResource(resource);
        return resource;
    }
}
