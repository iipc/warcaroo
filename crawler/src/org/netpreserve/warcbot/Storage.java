package org.netpreserve.warcbot;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import org.netpreserve.jwarc.*;
import org.netpreserve.warcbot.cdp.ResourceFetched;
import org.netpreserve.warcbot.cdp.domains.Network;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static java.nio.file.StandardOpenOption.*;
import static org.netpreserve.jwarc.MediaType.HTTP_REQUEST;
import static org.netpreserve.jwarc.MediaType.HTTP_RESPONSE;

public class Storage implements Closeable {
    private final Logger log = LoggerFactory.getLogger(Storage.class);
    private final WarcWriter warcWriter;
    final StorageDAO dao;
    private final TimeBasedEpochGenerator uuidGenerator;
    private final static DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private final SecureRandom random = new SecureRandom();
    private final String filename;

    public Storage(Path directory, StorageDAO dao) throws IOException {
        filename = "warcbot-" + DATE_FORMAT.format(Instant.now()) + "-" + randomId() + ".warc.gz";
        warcWriter = new WarcWriter(FileChannel.open(directory.resolve(filename),
                WRITE, CREATE, TRUNCATE_EXISTING), WarcCompression.GZIP);
        Warcinfo warcinfo = new Warcinfo.Builder()
                .filename(filename)
                .fields(Map.of("software", List.of("warcbot"),
                        "format", List.of("WARC File Format 1.0"),
                        "conformsTo", List.of("https://iipc.github.io/warc-specifications/specifications/warc-format/warc-1.0/")))
                .build();
        warcWriter.write(warcinfo);
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
    public List<Resource> save(Resource.Metadata metadata,
                               java.net.http.HttpResponse<byte[]> responseChain) throws IOException {
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
                    .build();

            var httpRequest = new org.netpreserve.jwarc.HttpRequest.Builder(request.method(), request.uri().getRawPath())
                    .addHeaders(stripHttp2Headers(request.headers()))
                    .build();

            var fetch = new ResourceFetched(request.uri().toString(),
                    httpRequest.serializeHeader(),
                    null,
                    httpResponse.serializeHeader(),
                    response.body(),
                    null,
                    metadata.ipAddress(),
                    metadata.fetchTimeMs(),
                    httpResponse.status(),
                    httpResponse.headers().first("Location").orElse(null),
                    httpResponse.contentType().base().toString(), new Network.ResourceType("RobotsTxt"),
                    response.version() == HttpClient.Version.HTTP_2 ? "h2" : null);
            Resource resource = save(metadata.pageId(), fetch);
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

    private void setBody(WarcTargetRecord.Builder<?, ?> builder, MediaType type, byte[] header, byte[] payload) {
        if (payload == null) {
            builder.body(type, header);
        } else {
            builder.body(type, Channels.newChannel(new SequenceInputStream(new ByteArrayInputStream(header),
                    new ByteArrayInputStream(payload))), header.length + payload.length);
            builder.payloadDigest(sha1(payload));
        }
    }

    public Resource save(UUID pageId, ResourceFetched fetch) throws IOException {
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
        var existingDuplicate = dao.findResourceByUrlAndPayload(fetch.url(),
                responseBodyLength,
                responseDigest == null ? null : responseDigest.prefixedBase32());
        if (existingDuplicate != null) {
            log.debug("Not saving duplicate of resource {}: {}", existingDuplicate.id(), fetch.url());
            return null;
        }

        Instant now = Instant.now();
        UUID responseUuid = uuidGenerator.construct(now.toEpochMilli());
        var warcResponseBuilder = new WarcResponse.Builder(fetch.url())
                .date(now)
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

        var warcRequestBuilder = new WarcRequest.Builder(fetch.url())
                .date(now)
                .recordId(uuidGenerator.construct(now.toEpochMilli()))
                .concurrentTo(warcResponse.id());
        setBody(warcRequestBuilder, HTTP_REQUEST, fetch.requestHeader(), fetch.requestBody());
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
                warcResponse.target(),
                warcResponse.date(),
                filename,
                responseOffset, responseLength, requestLength,
                fetch.status(), fetch.redirect(),
                fetch.responseType(),
                responseBodyLength,
                responseDigest,
                fetch.fetchTimeMs(), fetch.ipAddress(), fetch.type().value(),
                fetch.protocol());
        dao.addResource(resource);
        return resource;
    }
}
