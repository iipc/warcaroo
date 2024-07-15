package org.netpreserve.warcbot;

import org.intellij.lang.annotations.Language;
import org.netpreserve.warcbot.cdp.Runtime;
import org.netpreserve.warcbot.cdp.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class BrowserWindow implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BrowserWindow.class);
    private final Network network;
    private final Page page;
    private final Runtime runtime;
    private final Fetch fetch;
    private final Map<String, NetworkInfo> networkInfoMap = new ConcurrentHashMap<>();
    private final Map<String, PartialFetch> fetchesByNetworkId = new ConcurrentHashMap<>();
    private final Consumer<ResourceFetched> resourceHandler;
    private final RequestInterceptor requestInterceptor;
    private final AtomicReference<CompletableFuture<Double>> loadEvent = new AtomicReference<>(new CompletableFuture<>());
    private final IdleMonitor idleMonitor = new IdleMonitor();
    private final CDPSession cdpSession;
    private String frameId;

    public BrowserWindow(CDPSession cdpSession,
                         Consumer<ResourceFetched> resourceHandler,
                         RequestInterceptor requestInterceptor,
                         Tracker tracker) {
        this.cdpSession = cdpSession;
        this.resourceHandler = resourceHandler;
        this.fetch = cdpSession.domain(Fetch.class);
        this.network = cdpSession.domain(Network.class);
        this.page = cdpSession.domain(Page.class);
        this.runtime = cdpSession.domain(Runtime.class);
        this.requestInterceptor = requestInterceptor;

        network.onRequestWillBeSentExtraInfo(event ->
                getRequestInfo(event.requestId()).extraRequestHeaders = event.headers());
        network.onResponseReceived(this::handleResponseReceived);
        network.onResponseReceivedExtraInfo(event ->
                getRequestInfo(event.requestId()).rawResponseHeader = event.headersText());

        if (tracker != null) {
            network.onDataReceived(event -> tracker.updateDownloadedBytes(event.encodedDataLength() > 0 ?
                    event.encodedDataLength() : event.dataLength()));
        }

        page.onLoadEventFired(event -> loadEvent.get().complete(event.timestamp()));
        page.enable();

        fetch.onRequestPaused(this::handleRequestPaused);

        network.enable(0, 0, 0);
        fetch.enable(List.of(new Fetch.RequestPattern(null, null,
                "Request")));
    }

    private static byte[] formatResponseHeader(Fetch.RequestPaused event, String rawResponseHeader) {
        if (rawResponseHeader != null) {
            return rawResponseHeader.getBytes(StandardCharsets.US_ASCII);
        }
        var builder = new StringBuilder();
        String reason = event.responseStatusText();
        if (reason == null) reason = "";
        builder.append("HTTP/1.1 ").append(event.responseStatusCode()).append(" ").append(reason).append("\r\n");
        if (event.responseHeaders() == null) {
            log.warn("Null response headers: {}", event);
        } else {
            event.responseHeaders().forEach(entry ->
                    builder.append(entry.name()).append(": ").append(entry.value()).append("\r\n"));
        }
        builder.append("\r\n");
        return builder.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Constructs a HTTP/1.1 header from a CDP Network.Request.
     */
    private static byte[] formatRequestHeader(Network.Request request, Map<String, String> extraInfoHeaders) {
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
        return builder.toString().getBytes(StandardCharsets.US_ASCII);
    }

    public static void main(String[] args) throws Exception {
        try (var browserProcess = BrowserProcess.start(null);
             var visitor = browserProcess.newWindow(resourceFetched -> {
                 System.out.println("Resource: " + resourceFetched);
             }, null, null)) {
            //browser.webDriver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
            visitor.navigateTo(new Url(args[0]));
            visitor.forceLoadLazyImages();
            System.err.println("Scrolling...");
            visitor.scrollToBottom();
            System.err.println("Done scrolling");
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void handleRequestPaused(Fetch.RequestPaused event) {
        if (!event.isResponseStage()) {
            log.debug("Request paused {}", event);

            if (requestInterceptor != null) {
                RequestInterceptor.Response response;
                try {
                    response = requestInterceptor.intercept(event.request());
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
            return;
        }
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

    public void navigateTo(Url url) {
        loadEvent.getAndSet(new CompletableFuture<>()).complete(0.0);
        networkInfoMap.clear();
        fetchesByNetworkId.clear();
        var nav = page.navigate(url.toString());
        this.frameId = nav.frameId();
    }

    @Override
    public void close() {
        try {
            page.close();
        } catch (CDPException e) {
            if (!e.getMessage().contains("Session with given id not found")) {
                throw e;
            }
        } catch (Exception e) {
            // ignore
        }
        cdpSession.close();
    }

    @SuppressWarnings("unchecked")
    public <T> T eval(@Language("JavaScript") String script) {
        var evaluate = runtime.evaluate(script, 2000, true, false);
        if (evaluate.exceptionDetails() != null) {
            throw new RuntimeException(evaluate.exceptionDetails().toString());
        }
        return (T) evaluate.result().toJavaObject();
    }

    @SuppressWarnings("unchecked")
    public <T> T evalPromise(@Language("JavaScript") String script) {
        var evaluate = runtime.evaluate(script, 2000, true, true);
        if (evaluate.exceptionDetails() != null) {
            throw new JSException(evaluate.exceptionDetails().toString());
        }
        return (T) evaluate.result().toJavaObject();
    }

    private static class JSException extends RuntimeException {

        public JSException(String message) {
            super(message);
        }
    }

    public List<Url> extractLinks() {
        List<String> urls = eval("""
                (function() {
                    const links = new Set();
                    for (const el of document.querySelectorAll('a[href]')) {
                        const href = el.href;
                        if (href.startsWith('http://') || href.startsWith('https://')) {
                            links.add(href.replace(/#.*$/, ''));
                        }
                    }
                    return Array.from(links);
                })();
                """);
        return urls.stream().map(Url::new).toList();
    }

    public void forceLoadLazyImages() {
        eval("""
                    document.querySelectorAll('img[loading="lazy"]').forEach(img => {
                        img.loading = 'eager';
                        if (!img.complete) {
                          img.src = img.src;
                        }
                      });
                """);
    }

    public void scrollToBottom() {
        try {
            evalPromise("""
                
                    new Promise((doneCallback, reject) => {
                const startTime = Date.now();
                const maxScrollTime = 5000; // 5 seconds timeout
                const scrollInterval = 50;

                function scroll() {
                    if (window.innerHeight + window.scrollY >= document.body.offsetHeight) {
                        // We've reached the bottom of the page
                        doneCallback();
                        return;
                    }

                    if (Date.now() - startTime > maxScrollTime) {
                        // We've exceeded the timeout
                        doneCallback();
                        return;
    
                                   }
    
                    const scrollStep = document.scrollingElement.clientHeight * 0.2;

                    window.scrollBy({top: scrollStep, behavior: "instant"});
                    setTimeout(scroll, scrollInterval);
                }

                scroll();
                })
                """);
        } catch (JSException e) {
            log.warn("scrollToBottom threw {}", e.getMessage());
        }
    }

    public void navigateToBlank() throws InterruptedException {
        navigateTo(new Url("about:blank"));
        waitForLoadEvent();
        try {
            page.resetNavigationHistory();
        } catch (CDPException e) {
            // we might not be attached to a page
        }
    }

    public void waitForLoadEvent() throws InterruptedException {
        try {
            loadEvent.get().get(5, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            // ignore
        }
    }

    public void waitForNetworkIdle() throws InterruptedException {
        long start = System.currentTimeMillis();
        int n = idleMonitor.inflight;
        idleMonitor.waitUntilIdle();
        log.info("Waited {} ms for network idle (if={})", System.currentTimeMillis() - start, n);
    }

    public Url currentUrl() {
        return new Url(eval("document.location.href"));
    }

    public String title() {
        return eval("document.title");
    }

    private static class NetworkInfo {
        Map<String, String> extraRequestHeaders;
        String rawResponseHeader;
    }

    record PartialFetch(
            String url,
            byte[] requestHeader,
            byte[] requestBody,
            byte[] responseHeader,
            byte[] responseBody) {
    }

}
