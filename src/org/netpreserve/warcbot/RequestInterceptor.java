package org.netpreserve.warcbot;

import org.netpreserve.warcbot.cdp.*;
import org.netpreserve.warcbot.util.Url;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * Intercepts HTTP requests made by the browser.
 * <p>
 * This can be used to save downloaded resources or substitute responses such as by loading them from an external cache.
 */
public class RequestInterceptor {
    private static final Logger log = LoggerFactory.getLogger(RequestInterceptor.class);
    private final Browser browser;
    private final Fetch fetch;
    private final Network network;
    private final Map<String, NetworkInfo> networkInfoMap = new ConcurrentHashMap<>();
    private final Map<String, PartialFetch> fetchesByNetworkId = new ConcurrentHashMap<>();
    private final IdleMonitor idleMonitor;
    private final Tracker tracker;
    private final Consumer<ResourceFetched> resourceHandler;
    private final RequestHandler requestHandler;
    private final Path downloadPath;
    private static final ThreadFactory threadFactory = Thread.ofVirtual().name("RequestInterceptor", 0).factory();

    public RequestInterceptor(CDPSession cdpSession, IdleMonitor idleMonitor, Tracker tracker,
                              RequestHandler requestHandler, Consumer<ResourceFetched> resourceHandler,
                              Path downloadPath) {
        this.requestHandler = requestHandler;
        this.browser = cdpSession.domain(Browser.class);
        this.fetch = cdpSession.domain(Fetch.class);
        this.network = cdpSession.domain(Network.class);
        this.idleMonitor = idleMonitor;
        this.tracker = tracker;
        this.resourceHandler = resourceHandler;
        this.downloadPath = downloadPath;
        init();
    }

    private void init() {
        network.onRequestWillBeSentExtraInfo(event ->
                getRequestInfo(event.requestId()).extraRequestHeaders = event.headers());
        network.onResponseReceived(this::handleResponseReceived);
        network.onResponseReceivedExtraInfo(event ->
                getRequestInfo(event.requestId()).rawResponseHeader = event.headersText());
        if (tracker != null) {
            network.onDataReceived(event -> tracker.updateDownloadedBytes(event.encodedDataLength() > 0 ?
                    event.encodedDataLength() : event.dataLength()));
        }

        fetch.onRequestPaused(this::handleRequestOrResponsePaused);

        browser.setDownloadBehavior("allowAndName", downloadPath.toString(), true);
        browser.onDownloadProgress(this::handleDownloadProgress);
        browser.setDownloadBehavior("deny", null, false);

        network.enable(0, 0, 0);
        fetch.enable(List.of(new Fetch.RequestPattern(null, null, "Request")));
    }

    private void handleDownloadProgress(Browser.DownloadProgress event) {
        if ("completed".equals(event.state())) {
            // For now just delete the completed file, since the resourceHandler has already seen the response body.
            try {
                Files.deleteIfExists(downloadPath.resolve(event.guid()));
            } catch (IOException e) {
                log.warn("Error deleting downloaded file", e);
            }
        }
    }

    record PartialFetch(
            String url,
            byte[] requestHeader,
            byte[] requestBody,
            byte[] responseHeader,
            byte[] responseBody) {
    }

    private static class NetworkInfo {
        Map<String, String> extraRequestHeaders;
        String rawResponseHeader;
    }

    void reset() {
        networkInfoMap.clear();
        fetchesByNetworkId.clear();
    }

    /**
     * Called by the browser when either a request or a response is paused.
     */
    private void handleRequestOrResponsePaused(Fetch.RequestPaused event) {
        threadFactory.newThread(() -> {
            try {
                if (event.isResponseStage()) {
                    handleResponsePaused(event);
                } else {
                    handleRequestPaused(event);
                }
            } catch (Exception e) {
                log.error("Handler threw", e);
            }
        }).start();
    }

    private void handleRequestPaused(Fetch.RequestPaused event) {
        log.debug("Request paused {}", event);

        if (requestHandler != null) {
            RequestHandler.Response response;
            try {
                response = requestHandler.handle(event.request());
            } catch (Exception e) {
                log.error("Request interceptor threw", e);
                response = null;
            }
            if (response != null) {
                byte[] encodedHeaders = null;
                if (response.headers() != null) {
                    StringBuilder builder = new StringBuilder();
                    response.headers().map().forEach((name, values) -> {
                        for (String value : values) {
                            builder.append(name).append(": ").append(value).append("\0");
                        }
                    });
                    encodedHeaders = builder.toString().getBytes();
                }
                fetch.fulfillRequest(event.requestId(), response.status(), encodedHeaders,
                        response.body(), response.reason());
                return;
            }
        }

        idleMonitor.started();
        fetch.continueRequest(event.requestId(), true);
    }

    private void handleResponsePaused(Fetch.RequestPaused event) {
        log.debug("Response paused {} {}", event.requestId(), event.networkId());

        var info = event.networkId() == null ? null : networkInfoMap.remove(event.networkId());
        if (info == null) info = new NetworkInfo();

        var requestHeader = formatRequestHeader(event.request(), info.extraRequestHeaders);
        var requestBody = event.request().body();

        var responseHeader = formatResponseHeader(event, info.rawResponseHeader);
        byte[] responseBody;
        try {
            responseBody = fetch.getResponseBody(event.requestId()).body();
        } catch (CDPException e) {
            if (e.getCode() != -32000) throw e;
            responseBody = null;
        }

        var recording = new PartialFetch(event.request().url(), requestHeader, requestBody,
                responseHeader, responseBody);
        if (event.networkId() == null) {
            log.warn("No network id for request {} {}", event.requestId(), event);
        } else {
            fetchesByNetworkId.put(event.networkId(), recording);
        }

        fetch.continueResponse(event.requestId());
        idleMonitor.finished();
    }

    private void handleResponseReceived(Network.ResponseReceived event) {
        if (event.response().url().startsWith("chrome:")) return; // ignore
        var ipAddress = event.response().remoteIPAddress();
        var rec = fetchesByNetworkId.remove(event.requestId());
        if (resourceHandler == null) return;
        if (rec == null) {
            log.warn("No fetch for network id {} {}", event.requestId(), event.response().url());
            return;
        }
        long fetchTimeMs = (long) ((event.timestamp() - event.response().timing().requestTime()) * 1000);
        String responseType = event.response().headers().get("content-type");
        if (responseType != null) {
            responseType = responseType.replaceFirst(";.*$", "").strip();
        }
        String protocol = event.response().protocol();
        if (protocol != null) {
            protocol = protocol.toLowerCase(Locale.ROOT);
        }
        var fetch = new ResourceFetched(rec.url(), rec.requestHeader, rec.requestBody, rec.responseHeader,
                rec.responseBody, ipAddress, fetchTimeMs, event.response().status(),
                event.response().headers().get("Location"),
                responseType, event.type(), protocol);
        resourceHandler.accept(fetch);
    }

    private NetworkInfo getRequestInfo(String requestId) {
        return networkInfoMap.computeIfAbsent(requestId, k -> new NetworkInfo());
    }

    /**
     * Formats the response header for a given fetch request, using the raw response header string if available.
     * Omits any "Content-Encoding" headers because the browser only gives us the decoded response body.
     */
    static byte[] formatResponseHeader(Fetch.RequestPaused event, String rawResponseHeader) {
        if (rawResponseHeader != null) {
            rawResponseHeader = rawResponseHeader.replaceAll("(?mi)^Content-Encoding:.*?\\r?\\n", "");
            return rawResponseHeader.getBytes(US_ASCII);
        }
        var builder = new StringBuilder();
        String reason = event.responseStatusText();
        if (reason == null) reason = "";
        builder.append("HTTP/1.1 ").append(event.responseStatusCode()).append(" ").append(reason).append("\r\n");
        if (event.responseHeaders() == null) {
            log.warn("Null response headers: {}", event);
        } else {
            event.responseHeaders().stream()
                    .filter(entry -> !"Content-Encoding".equalsIgnoreCase(entry.name()))
                    .forEach(entry -> builder.append(entry.name()).append(": ").append(entry.value()).append("\r\n"));
        }
        builder.append("\r\n");
        return builder.toString().getBytes(US_ASCII);
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
}
