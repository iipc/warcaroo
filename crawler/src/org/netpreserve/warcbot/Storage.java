package org.netpreserve.warcbot;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import org.netpreserve.jwarc.*;
import org.netpreserve.warcbot.cdp.ResourceFetched;
import org.netpreserve.warcbot.cdp.domains.Network;
import org.netpreserve.warcbot.util.BareMediaType;
import org.netpreserve.warcbot.util.Url;
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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.nio.file.StandardOpenOption.*;
import static org.netpreserve.jwarc.MediaType.HTTP_REQUEST;
import static org.netpreserve.jwarc.MediaType.HTTP_RESPONSE;

public class Storage implements Closeable {
    private final Logger log = LoggerFactory.getLogger(Storage.class);
    private WarcWriter warcWriter;
    final Database db;
    private final TimeBasedEpochGenerator uuidGenerator;
    private final static DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private final SecureRandom random = new SecureRandom();
    private String filename;
    private final Path directory;
    private final Lock lock = new ReentrantLock();

    public Storage(Path directory, Database db) throws IOException {
        this.directory = directory;
        this.db = db;
        this.uuidGenerator = Generators.timeBasedEpochGenerator();
    }

    private void openWriter() throws IOException {
        if (warcWriter != null) {
            warcWriter.close();
        }
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
        lock.lock();
        try {
            if (warcWriter != null) warcWriter.close();
        } finally {
            lock.unlock();
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
            var httpResponse = new org.netpreserve.jwarc.HttpResponse.Builder(response.statusCode(), "")
                    .addHeaders(stripHttp2Headers(response.headers()))
                    .build();

            var httpRequest = new org.netpreserve.jwarc.HttpRequest.Builder(request.method(), request.uri().getRawPath())
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

    public Long save(long pageId, ResourceFetched fetch, Map<String, List<String>> metadata) throws IOException {
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
            return null;
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

        lock.lock();
        try {
            if (warcWriter == null || warcWriter.position() > 1024 * 1024 * 1024) {
                openWriter();
            }
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
        } finally {
            lock.unlock();
        }

        return db.inTransaction(db -> {
            long hostId = db.hosts().insertOrGetId(fetch.url().rhost());
            long domainId = db.domains().insertOrGetId(fetch.url().rdomain());
            Resource resource = new Resource(
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
            return id;
        });
    }
}
