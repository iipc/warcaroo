package org.netpreserve.warcbot.cdp;

import org.netpreserve.warcbot.cdp.domains.Fetch;
import org.netpreserve.warcbot.cdp.domains.Network;
import org.netpreserve.warcbot.util.Url;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.file.StandardOpenOption.*;

/**
 * Records the data of an individual request to a temporary file.
 * <p>
 * Normal order of events:
 * <ol>
 *   <li>Buffered data (returned by Network.streamResourceContent() in RequestPaused)
 *   <li>RequestWillBeSentExtraInfo
 *   <li>ResponseReceivedExtraInfo
 *   <li>ResponseReceived
 *   <li>ReceivedData x N
 *   <li>LoadingFinished
 * </ol>
 */
public class ResourceRecorder {
    private final static Logger log = LoggerFactory.getLogger(ResourceRecorder.class);
    private final Path downloadPath;
    private final Consumer<ResourceFetched> resourceHandler;
    private final Network.RequestId networkId;
    private final Network network;
    private FileChannel channel;
    private Network.Response response;
    private Network.Request request;
    private Map<String, String> fullRequestHeaders;
    private String rawResponseHeader;
    private Network.ResourceType resourceType;
    private long bytesWritten = 0;
    private long bytesReceived = 0;
    CompletableFuture<Void> completionFuture = new CompletableFuture<>();
    private double requestTimestamp;

    public ResourceRecorder(Network.RequestId networkId, Path downloadPath, Consumer<ResourceFetched> resourceHandler, Network network) {
        this.networkId = networkId;
        this.downloadPath = downloadPath;
        this.resourceHandler = resourceHandler;
        this.network = network;
    }

    /**
     * Constructs a HTTP/1.1 header from a CDP Network.Request.
     */
    static byte[] formatRequestHeader(Network.Request request, Map<String, String> extraInfoHeaders) {
        var builder = new StringBuilder();
        Url url = new Url(request.url());

        builder.append(request.method()).append(" ").append(url.pathAndQuery()).append(" HTTP/1.1\r\n");

        if (extraInfoHeaders != null) {
            extraInfoHeaders.forEach((name, value) -> {
                if (name.equals(":authority")) name = "host";
                if (name.startsWith(":")) return;
                builder.append(name).append(": ").append(value).append("\r\n");
            });
        } else {
            request.headers().forEach((name, value) -> builder.append(name).append(": ").append(value).append("\r\n"));
            if (!request.headers().containsKey("Host")) {
                builder.append("Host: ").append(url.hostAndPort()).append("\r\n");
            }
        }

        builder.append("\r\n");
        return builder.toString().getBytes(US_ASCII);
    }

    public void handleBufferedData(byte[] data) {
        wrap(log.atDebug()).addKeyValue("dataLength", data.length)
                .log("Received buffered data.");
        write(data);
    }

    public void handleRequestWillBeSent(Network.RequestWillBeSent event) {
        String url = event.request().url();
        log.atDebug().addKeyValue("networkId", networkId)
                .addKeyValue("url", url).log("Request will be sent");

        if (isRecordableUrl(url)) {
            // if we redirected, dispatch the redirect response.
            if (event.redirectResponse() != null) {
                response = event.redirectResponse();
                dispatchResource(event.timestamp());
            } else {
                network.streamResourceContent(networkId).thenAccept(this::handleBufferedData);
            }
        }

        this.request = event.request();
        this.resourceType = event.type();
        this.requestTimestamp = event.timestamp();
    }

    private static boolean isRecordableUrl(String url) {
        return url.startsWith("http:") || url.startsWith("https:");
    }

    public void handleRequestWillBeSentExtraInfo(Network.RequestWillBeSentExtraInfo event) {
        wrap(log.atDebug()).log("Will be sent extra info");
        this.fullRequestHeaders = event.headers();
    }

    public void handleResponseReceivedExtraInfo(Network.ResponseReceivedExtraInfo event) {
        wrap(log.atDebug()).log("Response received extra info");
        this.rawResponseHeader = event.headersText();
    }

    public void handleRequestPaused(Fetch.RequestPaused event) {
        wrap(log.atDebug()).log("Request paused");
        this.request = event.request();
    }

    public void handleResponseReceived(Network.ResponseReceived event) {
        wrap(log.atDebug()).log("Response received");
        this.response = event.response();
        this.resourceType = event.type();
    }

    public void handleDataReceived(Network.DataReceived event) {
        byte[] data = event.data();
        if (data != null && data.length > 0) {
            wrap(log.atDebug()).addKeyValue("dataLength", data.length).log("Received data");
            write(data);
        }
        bytesReceived += event.dataLength();
    }

    private void write(byte[] data) {
        try {
            if (channel == null) {
                this.channel = FileChannel.open(downloadPath.resolve(UUID.randomUUID().toString()),
                        CREATE_NEW, WRITE, READ, DELETE_ON_CLOSE);
            }
            Channels.newOutputStream(channel).write(data);
            bytesWritten += data.length;
        } catch (IOException e) {
            log.error("Failed to write request data", e);
            closeChannel();
        }
    }

    public void handleLoadingFinished(Network.LoadingFinished event) {
        wrap(log.atDebug()).log("Loading finished");

        if (bytesWritten < bytesReceived && request != null && isRecordableUrl(request.url())) {
            // Sometimes the browser can finish the request before it processes our streamResourceContent() command.
            // Unfortunately this even happens if we issue it before resuming a paused request.
            log.trace("Received {} but only wrote {}", bytesReceived, bytesWritten);
            network.getResponseBodyAsync(networkId)
                    .whenComplete((responseBody, ex) -> {
                        try {
                            if (ex == null) {
                                write(responseBody.body());
                                dispatchResource(event.timestamp());
                            } else {
                                log.atError().addKeyValue("networkId", network).log("Error getting response body", ex);
                            }
                        } finally {
                            closeChannel();
                            completionFuture.complete(null);
                        }
                    });
            return;
        }

        try {
            dispatchResource(event.timestamp());
        } finally {
            closeChannel();
            completionFuture.complete(null);
        }
    }

    private void dispatchResource(double timestamp) {
        if (request == null) {
            wrap(log.atWarn()).log("never received request");
            return;
        }
        if (response == null) {
            wrap(log.atWarn()).log("never received response");
            return;
        }
        if (!isRecordableUrl(request.url())) return;
        byte[] requestHeader = formatRequestHeader(request, fullRequestHeaders);
        byte[] responseHeader = formatResponseHeader(response, rawResponseHeader);
        long fetchTimeMs;
        if (response.timing() != null) {
            fetchTimeMs = (long) ((timestamp - response.timing().requestTime()) * 1000);
        } else {
            fetchTimeMs = (long) ((timestamp - requestTimestamp) * 1000);
        }
        String redirect = response.headers().get("location");
        String responseType = response.headers().get("Content-Type");
        rewindChannel();
        resourceHandler.accept(new ResourceFetched(response.url(), requestHeader, request.body(), responseHeader,
                null, channel, response.remoteIPAddress(), fetchTimeMs, response.status(),
                redirect, responseType, resourceType, response.protocol()));
    }

    public void handleLoadingFailed(Network.LoadingFailed event) {
        wrap(log.atDebug()).log("Loading failed");
        closeChannel();
        completionFuture.complete(null);
    }

    private LoggingEventBuilder wrap(LoggingEventBuilder builder) {
         builder = builder.addKeyValue("networkId",networkId);
         if (request != null) {
             builder = builder.addKeyValue("url", request.url());
         }
         return builder;
    }

    private void rewindChannel() {
        if (channel == null) return;
        try {
            channel.position(0);
        } catch (IOException e) {
            log.error("IO error", e);
            closeChannel();
        }
    }

    private void closeChannel() {
        if (channel == null) return;
        try {
            channel.close();
            channel = null;
        } catch (IOException e) {
            log.atWarn().addKeyValue("networkId", networkId).log("Failed to close channel", e);
        }
    }

    /**
     * Formats the response header for a given fetch request, using the raw response header string if available.
     * Omits any "Content-Encoding" headers because the browser only gives us the decoded response body.
     */
    static byte[] formatResponseHeader(Network.Response response, String rawResponseHeader) {
        if (rawResponseHeader != null) {
            rawResponseHeader = rawResponseHeader.replaceAll("(?mi)^Content-Encoding:.*?\\r?\\n", "");
            return rawResponseHeader.getBytes(US_ASCII);
        }
        var builder = new StringBuilder();
        String reason = response.statusText();
        if (reason == null) reason = "";
        builder.append("HTTP/1.1 ").append(response.status()).append(" ").append(reason).append("\r\n");
        response.headers().forEach((name, value) -> {
            if (name.equalsIgnoreCase("content-encoding")) return;
            builder.append(name).append(": ").append(value).append("\r\n");
        });
        builder.append("\r\n");
        return builder.toString().getBytes(US_ASCII);
    }
}
