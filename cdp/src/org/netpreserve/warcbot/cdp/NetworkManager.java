package org.netpreserve.warcbot.cdp;

import org.netpreserve.warcbot.cdp.domains.Browser;
import org.netpreserve.warcbot.cdp.domains.Fetch;
import org.netpreserve.warcbot.cdp.domains.Network;
import org.netpreserve.warcbot.cdp.protocol.CDPSession;
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

public class NetworkManager {
    private static final Logger log = LoggerFactory.getLogger(NetworkManager.class);
    private final Browser browser;
    private final Fetch fetch;
    private final Network network;
    private final Map<Network.RequestId, ResourceRecorder> recorders = new ConcurrentHashMap<>();
    private final IdleMonitor idleMonitor;
    private final Tracker tracker;
    private final Consumer<ResourceFetched> resourceHandler;
    private final RequestHandler requestHandler;
    private final Path downloadPath;
    private Predicate<String> blocker = url -> false;

    public NetworkManager(CDPSession cdpSession, IdleMonitor idleMonitor, Tracker tracker,
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

        browser.setDownloadBehavior("allowAndName", downloadPath.toString(), true);
        browser.onDownloadProgress(this::handleDownloadProgress);
        browser.setDownloadBehavior("deny", null, false);

        network.enable(10*1024*1024, 10*1024*1024, 10*1024*1024);
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

    public void block(Predicate<String> predicate) {
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
        if (tracker != null) {
            tracker.updateDownloadedBytes(event.encodedDataLength() > 0 ?
                    event.encodedDataLength() : event.dataLength());
        }

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
        }
    }

    /**
     * Called by the browser when either a request or a response is paused.
     */
    private void handleRequestOrResponsePaused(Fetch.RequestPaused event) {
        if (event.isResponseStage()) {
            //handleResponsePaused(event);
        } else {
            handleRequestPaused(event);
        }
    }

    private void handleRequestPaused(Fetch.RequestPaused event) {
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

        fetch.continueRequestAsync(event.requestId(), false);
    }

    private ResourceRecorder getOrCreateRecorder(Network.RequestId requestId) {
        return recorders.computeIfAbsent(requestId, id -> {
            var recorder = new ResourceRecorder(id, downloadPath, resourceHandler, network);
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
            CompletableFuture.allOf(recorders.values()
                            .stream().map(recorder -> recorder.completionFuture)
                            .toArray(CompletableFuture[]::new))
                    .get(120, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException | InterruptedException e) {
            log.warn("waitForLoadingResources threw", e);
        }
    }
}
