package org.netpreserve.warcaroo;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import org.netpreserve.jwarc.*;
import org.netpreserve.warcaroo.cdp.ResourceFetched;
import org.netpreserve.warcaroo.cdp.domains.Network;
import org.netpreserve.warcaroo.config.StorageConfig;
import org.netpreserve.warcaroo.util.BareMediaType;
import org.netpreserve.warcaroo.util.Url;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

import static org.netpreserve.jwarc.MediaType.HTTP_REQUEST;
import static org.netpreserve.jwarc.MediaType.HTTP_RESPONSE;

public class Storage implements Closeable {
    private final Logger log = LoggerFactory.getLogger(Storage.class);
    private final BlockingDeque<WarcRotator> warcPool;
    final Database db;
    private final TimeBasedEpochGenerator uuidGenerator;
    private final int poolSize = 8;

    public Storage(Path directory, Database db, StorageConfig config) throws IOException {
        this.db = db;
        this.uuidGenerator = Generators.timeBasedEpochGenerator();
        warcPool = new LinkedBlockingDeque<>(poolSize);

        Path warcsDir = directory.resolve("warcs");
        Files.createDirectories(warcsDir);

        String prefix = config.prefix();
        if (prefix == null) prefix = "warcaroo";
        for (int i = 0; i < poolSize; i++) {
            warcPool.add(new WarcRotator(warcsDir, prefix));
        }
    }

    @Override
    public void close() throws IOException {
        for (int i = 0; i < poolSize; i++) {
            try {
                warcPool.take().close();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private WarcDigest sha1(byte[] data) {
        try {
            if (data == null) return null;
            var digest = MessageDigest.getInstance("SHA-1");
            digest.update(data);
            return new WarcDigest(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private WarcDigest sha1(InputStream stream) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[8192];
            while (true) {
                int n = stream.read(buffer);
                if (n == -1) break;
                digest.update(buffer, 0, n);
            }
            return new WarcDigest(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Stores an HTTP response chain.
     *
     * <p>This method processes a chain of HTTP responses (where each response may have a reference
     * to the previous response in the chain) and saves each response along with its associated request.
     * </p>
     */
    public void save(Resource.Metadata metadata,
                     java.net.http.HttpResponse<byte[]> responseChain,
                     Instant responseTime) throws IOException {
        // walk the response chain backwards and then reverse it so the responses are in order they were made
        var responses = new ArrayList<java.net.http.HttpResponse<byte[]>>();
        for (var response = responseChain; response != null; response = response.previousResponse().orElse(null)) {
            responses.add(response);
        }
        Collections.reverse(responses);

        for (var response : responses) {
            var request = response.request();
            var httpResponse = new HttpResponse.Builder(response.statusCode(), "")
                    .addHeaders(stripHttp2Headers(response.headers()))
                    .build();

            var httpRequest = new HttpRequest.Builder(request.method(), request.uri().getRawPath())
                    .addHeaders(stripHttp2Headers(request.headers()))
                    .build();

            byte[] responseHeader = httpResponse.serializeHeader();
            var fetch = new ResourceFetched(
                    request.method(),
                    new Url(request.uri().toString()),
                    httpRequest.serializeHeader(),
                    null,
                    responseHeader,
                    response.body(),
                    null,
                    metadata.ipAddress(),
                    metadata.fetchTimeMs(),
                    httpResponse.status(),
                    httpResponse.headers().first("Location").orElse(null),
                    new BareMediaType(httpResponse.contentType().base().toString()),
                    new Network.ResourceType("Robots"),
                    response.version() == HttpClient.Version.HTTP_2 ? "h2" : null,
                    responseHeader.length + response.body().length, null, null, null,
                    responseTime);
            save(metadata.pageId(), fetch, null);
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

    private void setBody(WarcTargetRecord.Builder<?, ?> builder, MediaType type, byte[] header, byte[] payload) {
        if (payload == null) {
            builder.body(type, header);
        } else {
            builder.body(type, Channels.newChannel(new SequenceInputStream(new ByteArrayInputStream(header),
                    new ByteArrayInputStream(payload))), header.length + payload.length);
            builder.payloadDigest(sha1(payload));
        }
    }

    public Resource save(long pageId, ResourceFetched fetch, Map<String, List<String>> metadata) throws IOException {
        long responseBodyLength;
        WarcDigest responseDigest;
        if (fetch.responseBodyChannel() != null) {
            fetch.responseBodyChannel().position(0);
            responseDigest = sha1(Channels.newInputStream(fetch.responseBodyChannel()));
            responseBodyLength = fetch.responseBodyChannel().size();
            fetch.responseBodyChannel().position(0);
        } else if (fetch.responseBody() != null) {
            responseDigest = sha1(fetch.responseBody());
            responseBodyLength = fetch.responseBody().length;
        } else {
            responseDigest = null;
            responseBodyLength = 0;
        }
        var existingDuplicate = db.resources().findByUrlAndPayload(fetch.url().toString(),
                responseBodyLength,
                responseDigest == null ? null : responseDigest.prefixedBase32());
        if (existingDuplicate != null) {
            log.debug("Not saving duplicate of resource {}: {}", existingDuplicate.responseUuid(), fetch.url());
            return existingDuplicate;
        }

        Instant responseTime = fetch.responseTime();
        UUID responseUuid = uuidGenerator.construct(responseTime.toEpochMilli());
        var warcResponseBuilder = new WarcResponse.Builder(fetch.url().toString())
                .date(responseTime)
                .recordId(responseUuid);
        if (fetch.responseBodyChannel() != null) {
            var headerPlusBody = new SequenceInputStream(new ByteArrayInputStream(fetch.responseHeader()),
                    Channels.newInputStream(fetch.responseBodyChannel()));
            warcResponseBuilder.body(HTTP_RESPONSE, Channels.newChannel(headerPlusBody),
                    fetch.responseHeader().length + fetch.responseBodyChannel().size());
            warcResponseBuilder.payloadDigest(responseDigest);
        } else {
            setBody(warcResponseBuilder, HTTP_RESPONSE, fetch.responseHeader(), fetch.responseBody());
        }
        if (fetch.protocol() != null) warcResponseBuilder.addHeader("WARC-Protocol", fetch.protocol());
        WarcResponse warcResponse = warcResponseBuilder.build();

        var warcRequestBuilder = new WarcRequest.Builder(fetch.url().toString())
                .date(responseTime)
                .recordId(uuidGenerator.construct(responseTime.toEpochMilli()))
                .concurrentTo(warcResponse.id());
        setBody(warcRequestBuilder, HTTP_REQUEST, fetch.requestHeader(), fetch.requestBody());
        WarcRequest warcRequest = warcRequestBuilder.build();

        WarcMetadata warcMetadata;
        if (metadata != null) {
            warcMetadata = new WarcMetadata.Builder()
                    .recordId(uuidGenerator.construct(responseTime.toEpochMilli()))
                    .targetURI(fetch.url().toString())
                    .date(responseTime)
                    .concurrentTo(warcResponse.id())
                    .fields(metadata)
                    .build();
        } else {
            warcMetadata = null;
        }

        long responseOffset;
        long responseLength;
        long requestLength;
        long metadataLength;

        String filename;
        WarcRotator rotator;
        try {
            rotator = warcPool.takeFirst();
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
        try {
            var warcWriter = rotator.get();
            filename = rotator.filename();
            responseOffset = warcWriter.position();
            warcWriter.write(warcResponse);
            responseLength = warcWriter.position() - responseOffset;
            warcWriter.write(warcRequest);
            requestLength = warcWriter.position() - responseOffset - responseLength;
            if (warcMetadata != null) {
                warcWriter.write(warcMetadata);
                metadataLength = warcWriter.position() - responseOffset - responseLength - requestLength;
            } else {
                metadataLength = 0;
            }
            if (warcWriter.position() > 1024 * 1024 * 1024) {
                rotator.close();
            }
        } finally {
            // put it back at the front of the pool to minimize the number of active files
            warcPool.addFirst(rotator);
        }

        return db.inTransaction(db -> {
            long hostId = db.hosts().insertOrGetId(fetch.url().rhost());
            long domainId = db.domains().insertOrGetId(fetch.url().rdomain());
            Resource resource = new Resource(
                    null,
                    responseUuid,
                    pageId,
                    fetch.method(),
                    fetch.url(),
                    hostId,
                    domainId,
                    responseTime,
                    filename,
                    responseOffset,
                    responseLength,
                    requestLength,
                    metadataLength,
                    fetch.status(),
                    fetch.redirect(),
                    fetch.responseType(),
                    responseBodyLength,
                    responseDigest,
                    fetch.fetchTimeMs(),
                    fetch.ipAddress(),
                    fetch.type(),
                    fetch.protocol(),
                    fetch.transferred());
            long id = db.resources().add(resource);
            db.pages().addResourceToPage(pageId, resource.payloadSize());
            db.progress().addResourceProgress(resource.payloadSize());
            return resource.withId(id);
        });
    }
}
