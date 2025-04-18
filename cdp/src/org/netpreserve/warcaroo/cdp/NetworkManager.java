package org.netpreserve.warcaroo.cdp;

import org.netpreserve.warcaroo.cdp.domains.*;
import org.netpreserve.warcaroo.cdp.protocol.CDPSession;
import org.netpreserve.warcaroo.util.Url;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.*;

public class NetworkManager {
    private static final Logger log = LoggerFactory.getLogger(NetworkManager.class);
    private final Browser browser;
    private final Fetch fetch;
    private final Network network;
    private final Map<Network.RequestId, ResourceRecorder> recorders = new ConcurrentHashMap<>();
    private final Map<String, ResourceRecorder> downloadRecorders = new ConcurrentHashMap<>();
    private final IdleMonitor idleMonitor;
    private final Consumer<ResourceFetched> resourceHandler;
    private final RequestHandler requestHandler;
    private final Path downloadPath;
    private final CDPSession cdpSession;
    private Predicate<Url> blocker = url -> false;
    private volatile boolean captureResponseBodies = true;
    private volatile ResourceRecorder possibleDownloadRecorder = null;
    private Url preventNavigationUrl;

    public NetworkManager(CDPSession cdpSession, IdleMonitor idleMonitor,
                          RequestHandler requestHandler, Consumer<ResourceFetched> resourceHandler,
                          Path downloadPath) {
        this.cdpSession = cdpSession;
        this.requestHandler = requestHandler;
        this.browser = cdpSession.domain(Browser.class);
        this.fetch = cdpSession.domain(Fetch.class);
        this.network = cdpSession.domain(Network.class);
        this.idleMonitor = idleMonitor;
        this.resourceHandler = resourceHandler;
        this.downloadPath = downloadPath;
        init();
    }

    private void init() {
        try {
            Files.createDirectories(downloadPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create directory: " + downloadPath, e);
        }

        network.onRequestWillBeSent(this::handleRequestWillBeSent);
        network.onRequestWillBeSentExtraInfo(this::handleRequestWillBeSentExtraInfo);
        network.onResponseReceived(this::handleResponseReceived);
        network.onResponseReceivedExtraInfo(this::handleResponseReceivedExtraInfo);
        network.onDataReceived(this::handleDataReceived);
        network.onLoadingFinished(this::handleLoadingFinished);
        network.onLoadingFailed(this::handleLoadingFailed);

        fetch.onRequestPaused(this::handleRequestOrResponsePaused);

        browser.onDownloadProgress(this::handleDownloadProgress);
        browser.onDownloadWillBegin(this::handleDownloadWillBegin);
        browser.setDownloadBehavior("allowAndName", downloadPath.toString(), true);

        network.enable(10*1024*1024, 10*1024*1024, 10*1024*1024);
        fetch.enable(List.of(new Fetch.RequestPattern(null, null, "Request")));
    }

    private void handleDownloadWillBegin(Browser.DownloadWillBegin downloadWillBegin) {
        if (possibleDownloadRecorder != null
            && possibleDownloadRecorder.frameId.equals(downloadWillBegin.frameId())
            && possibleDownloadRecorder.request.url().equals(downloadWillBegin.url())) {
            downloadRecorders.put(downloadWillBegin.guid(), possibleDownloadRecorder);
            possibleDownloadRecorder = null;
        } else {
            log.atWarn().addKeyValue("url", downloadWillBegin.url())
                    .addKeyValue("frameId", downloadWillBegin.frameId())
                    .addKeyValue("guid", downloadWillBegin.guid())
                    .log("Canceling unexpected download");
            browser.cancelDownload(downloadWillBegin.guid());
        }
    }

    private void handleDownloadProgress(Browser.DownloadProgress event) {
        switch (event.state()) {
            case "completed" -> {
                var recorder = downloadRecorders.remove(event.guid());
                if (recorder != null) {
                    recorder.handleDownloadCompleted(event);
                }
                cleanupDownload(event);
            }
            case "canceled" -> {
                downloadRecorders.remove(event.guid());
                cleanupDownload(event);
            }
            case "inProgress" -> {
            }
            default -> log.warn("Unexpected Network.downloadProgress state: {}", event.state());
        }
    }

    private void cleanupDownload(Browser.DownloadProgress event) {
        try {
            Files.deleteIfExists(downloadPath.resolve(event.guid()));
        } catch (IOException e) {
            log.warn("Error deleting downloaded file", e);
        }
    }

    public void block(Predicate<Url> predicate) {
        this.blocker = predicate;
    }

    private void handleResponseReceivedExtraInfo(Network.ResponseReceivedExtraInfo event) {
        getOrCreateRecorder(event.requestId()).handleResponseReceivedExtraInfo(event);
    }

    private void handleRequestWillBeSent(Network.RequestWillBeSent event) {
        getOrCreateRecorder(event.requestId()).handleRequestWillBeSent(event);
    }

    private void handleRequestWillBeSentExtraInfo(Network.RequestWillBeSentExtraInfo event) {
        getOrCreateRecorder(event.requestId()).handleRequestWillBeSentExtraInfo(event);
    }

    private void handleDataReceived(Network.DataReceived event) {
        var recorder = recorders.get(event.requestId());
        if (recorder != null) {
            recorder.handleDataReceived(event);
        }
    }

    private void handleLoadingFinished(Network.LoadingFinished event) {
        var recorder = recorders.get(event.requestId());
        if (recorder != null) {
            recorder.handleLoadingFinished(event);
        }
    }

    private void handleLoadingFailed(Network.LoadingFailed event) {
        var recorder = recorders.get(event.requestId());
        if (recorder != null) {
            recorder.handleLoadingFailed(event);
            if (event.canceled() && event.errorText().equals("net::ERR_ABORTED")
                && recorder.resourceType.isDocument()) {
                // this might be the frame load being cancelled to start a download
                possibleDownloadRecorder = recorder;
            }
        }
    }

    /**
     * Called by the browser when either a request or a response is paused.
     */
    private void handleRequestOrResponsePaused(Fetch.RequestPaused event) {
        if (event.isResponseStage()) {
            handleResponsePaused(event);
        } else {
            handleRequestPaused(event);
        }
    }

    private void handleResponsePaused(Fetch.RequestPaused event) {
        fetch.continueResponseAsync(event.requestId());
    }

    private void handleRequestPaused(Fetch.RequestPaused event) {
        if (event.frameId().value().equals(cdpSession.targetId()) &&
            event.resourceType().isDocument() &&
            event.request().url().equals(preventNavigationUrl)) {
            fetch.failRequestAsync(event.requestId(), "Aborted");
            return;
        }

        if (blocker.test(event.request().url())) {
            log.debug("Blocked request for {}", event.request().url());
            fetch.failRequestAsync(event.requestId(), "BlockedByClient");
            return;
        }

        if (event.networkId() != null) {
            var recorder = getOrCreateRecorder(event.networkId());
            recorder.handleRequestPaused(event);
        }

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
                fetch.fulfillRequestAsync(event.requestId(), response.status(), encodedHeaders,
                        response.body(), response.reason());
                return;
            }
        }

        fetch.continueRequestAsync(event.requestId(), true);
    }

    private ResourceRecorder getOrCreateRecorder(Network.RequestId requestId) {
        return recorders.computeIfAbsent(requestId, id -> {
            var recorder = new ResourceRecorder(id, downloadPath, resourceHandler, network, captureResponseBodies);
            recorder.completionFuture.whenComplete((v, t) -> {
                recorders.remove(requestId);
                idleMonitor.finished();
            });
            return recorder;
        });
    }

    private void handleResponseReceived(Network.ResponseReceived event) {
        var recorder = recorders.get(event.requestId());
        if (recorder != null) {
            recorder.handleResponseReceived(event);
        }
    }

    public void waitForLoadingResources() {
        try {
            var futures = Stream.concat(recorders.values().stream(), downloadRecorders.values().stream())
                    .map(recorder -> recorder.completionFuture)
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).get(120, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException | InterruptedException e) {
            log.warn("waitForLoadingResources threw", e);
        }
    }

    public void captureResponseBodies(boolean captureResponseBodies) {
        this.captureResponseBodies = captureResponseBodies;
    }

    /**
     * Aborts requests for top-level navigation to a given URL.
     */
    void preventNavigation(Url url) {
        this.preventNavigationUrl = url;
    }
}
