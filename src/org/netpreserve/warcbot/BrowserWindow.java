package org.netpreserve.warcbot;

import org.intellij.lang.annotations.Language;
import org.netpreserve.warcbot.cdp.Runtime;
import org.netpreserve.warcbot.cdp.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class BrowserWindow implements AutoCloseable {
    private final Logger log = LoggerFactory.getLogger(BrowserWindow.class);
    private final Browser browser;
    private final Network network;
    private final Page page;
    private final Runtime runtime;
    private final Fetch fetch;
    private final Map<String, NetworkInfo> networkInfoMap = new ConcurrentHashMap<>();
    private final Map<String, PartialFetch> fetchesByNetworkId = new ConcurrentHashMap<>();
    private final Consumer<ResourceFetched> resourceHandler;
    private final RequestInterceptor requestInterceptor;

    public BrowserWindow(CDPSession session, Consumer<ResourceFetched> resourceHandler, RequestInterceptor requestInterceptor) {
        this.resourceHandler = resourceHandler;
        this.browser = session.domain(Browser.class);
        this.fetch = session.domain(Fetch.class);
        this.network = session.domain(Network.class);
        this.page = session.domain(Page.class);
        this.runtime = session.domain(Runtime.class);
        this.requestInterceptor = requestInterceptor;

        network.onRequestWillBeSentExtraInfo(event ->
                getRequestInfo(event.requestId()).extraRequestHeaders = event.headers());
        network.onResponseReceived(this::handleResponseReceived);
        network.onResponseReceivedExtraInfo(event ->
                getRequestInfo(event.requestId()).rawResponseHeader = event.headersText());

        fetch.onRequestPaused(this::handleRequestPaused);

        network.enable(0, 0, 0);
        fetch.enable(List.of(new Fetch.RequestPattern(null, null,
                requestInterceptor == null ? "Response" : "Request")));
    }

    private static byte[] formatResponseHeader(Fetch.RequestPaused event, String rawResponseHeader) {
        if (rawResponseHeader != null) {
            return rawResponseHeader.getBytes(StandardCharsets.US_ASCII);
        }
        var builder = new StringBuilder();
        String reason = event.responseStatusText();
        if (reason == null) reason = "";
        builder.append("HTTP/1.1 ").append(event.responseStatusCode()).append(" ").append(reason).append("\r\n");
        event.responseHeaders().forEach(entry ->
                builder.append(entry.name()).append(": ").append(entry.value()).append("\r\n"));
        builder.append("\r\n");
        return builder.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Constructs a HTTP/1.1 header from a CDP Network.Request.
     */
    private static byte[] formatRequestHeader(Network.Request request, Map<String, String> extraInfoHeaders) {
        var builder = new StringBuilder();
        URI uri = URI.create(request.url());

        builder.append(request.method()).append(" ").append(uri.getPath()).append(" HTTP/1.1\r\n");

        if (extraInfoHeaders != null) {
            extraInfoHeaders.forEach((name, value) -> builder.append(name).append(": ").append(value).append("\r\n"));
        } else {
            request.headers().forEach((name, value) -> builder.append(name).append(": ").append(value).append("\r\n"));
            if (!request.headers().containsKey("Host")) {
                builder.append("Host: ").append(uri.getHost());
                if (uri.getPort() >= 0) {
                    builder.append(":").append(uri.getPort());
                }
                builder.append("\r\n");
            }
        }
        builder.append("\r\n");
        return builder.toString().getBytes(StandardCharsets.US_ASCII);
    }

    public static void main(String[] args) throws Exception {
        AtomicInteger counter = new AtomicInteger();
        try (var browserProcess = BrowserProcess.start(null);
             var visitor = browserProcess.newWindow(resourceFetched -> {
                 System.out.println("Resource: " + resourceFetched);
             })) {
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
        var fetch = new ResourceFetched(rec.url(), rec.requestHeader, rec.requestBody, rec.responseHeader,
                rec.responseBody, ipAddress, fetchTimeMs, event.response().status(),
                event.response().headers().get("Location"),
                responseType);
        resourceHandler.accept(fetch);
    }

    private NetworkInfo getRequestInfo(String requestId) {
        return networkInfoMap.computeIfAbsent(requestId, k -> new NetworkInfo());
    }

    public void navigateTo(Url url) {
        page.navigate(url.toString());
    }

    @Override
    public void close() {
        log.debug("Closing web driver");
        browser.close();
    }

    @SuppressWarnings("unchecked")
    public <T> T eval(@Language("JavaScript") String script) {
        var evaluate = runtime.evaluate(script, 2000, true);
        if (evaluate.exceptionDetails() != null) {
            throw new RuntimeException(evaluate.exceptionDetails().toString());
        }
        return (T) evaluate.result().toJavaObject();
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
        // FIXME: wait for promise?
//        eval("""
//                const doneCallback = arguments[arguments.length - 1];
//                const startTime = Date.now();
//                const maxScrollTime = 5000; // 5 seconds timeout
//                const scrollStep = window.innerHeight / 2; // Scroll half a viewport at a time
//                const scrollInterval = 100; // Interval between scrolls in milliseconds
//
//                function scroll() {
//                    if (window.innerHeight + window.scrollY >= document.body.offsetHeight) {
//                        // We've reached the bottom of the page
//                        doneCallback();
//                        return;
//                    }
//
//                    if (Date.now() - startTime > maxScrollTime) {
//                        // We've exceeded the timeout
//                        doneCallback();
//                        return;
//                    }
//
//                    window.scrollBy(0, scrollStep);
//                    setTimeout(scroll, scrollInterval);
//                }
//
//                scroll();
//                """);
    }

    public void navigateToBlank() {
        navigateTo(new Url("about:blank"));
        try {
            page.resetNavigationHistory();
        } catch (CDPException e) {
            // we might not be attached to a page
        }
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
