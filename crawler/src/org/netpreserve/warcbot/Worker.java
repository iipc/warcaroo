package org.netpreserve.warcbot;

import org.netpreserve.warcbot.cdp.*;
import org.netpreserve.warcbot.cdp.domains.Page;
import org.netpreserve.warcbot.cdp.protocol.CDPException;
import org.netpreserve.warcbot.util.Url;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Worker {
    private static final Logger log = LoggerFactory.getLogger(Worker.class);
    final String id;
    Navigator navigator;
    private final BrowserManager browserManager;
    private final Frontier frontier;
    private final Storage storage;
    private final Database db;
    private final RobotsTxtChecker robotsTxtChecker;
    private final Config config;
    private Thread thread;
    private volatile boolean closed = false;
    private volatile Long pageId;
    private final Set<String> outlinks = Collections.newSetFromMap(new ConcurrentSkipListMap<>());
    private volatile Info info;
    private FrontierUrl frontierUrl;

    public Worker(String id, BrowserManager browserManager, Frontier frontier, Storage storage, Database db, RobotsTxtChecker robotsTxtChecker, Config config) {
        this.id = id;
        this.browserManager = browserManager;
        this.frontier = frontier;
        this.storage = storage;
        this.db = db;
        this.robotsTxtChecker = robotsTxtChecker;
        this.config = config;
        info = new Info(id, null, null, Instant.now());
    }

    private void handleSubresource(ResourceFetched resource) {
        if (pageId == null) return;
        String hopType;
        if (resource.method().equals("GET")) {
            if (resource.type().value().equals("Manifest")) {
                hopType = "M";
            } else {
                hopType = "E";
            }
        } else {
            hopType = "S";
        }
        outlinks.add(resource.url() + " " + hopType + " =" + resource.type().value());
        try {
            storage.save(pageId, resource, null);
        } catch (IOException e) {
            log.error("Failed to save resource", e);
        }
    }

    public void closeAsyncGraceful() {
        closed = true;
    }

    void closeAsync() {
        if (closed) return;
        closed = true;
        thread.interrupt();
    }

    void close() {
        closeAsync();
        try {
            thread.join(10000);
        } catch (InterruptedException e) {
            // OK
        }
        if (navigator != null) {
            navigator.close();
        }
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for thread to close", e);
        }
    }

    void run() throws Exception {
        while (!closed) {
            frontierUrl = frontier.takeNext();
            if (frontierUrl == null) {
                log.info("No work available for worker {}", id);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }

            pageId = db.pages().create(frontierUrl.url(), frontierUrl.hostId(), frontierUrl.domainId(), Instant.now());
            var startTime = System.nanoTime();

            updateInfo(new Info(id, pageId, frontierUrl.url(), Instant.now()));

            log.atInfo().addKeyValue("pageId", pageId).addKeyValue("url", frontierUrl.url()).log("Considering page");

            try {
                if (!robotsTxtChecker.checkAllowed(pageId, frontierUrl.url())) {
                    frontier.release(frontierUrl, FrontierUrl.State.ROBOTS_EXCLUDED);
                    continue;
                }

                if (navigator == null) {
                    navigator = browserManager.newWindow(this::handleSubresource, null);
                }
                navigator.setUserAgent(config.getCrawlSettings().userAgent());
                navigator.block(config.getBlockPredicate());

                // prevent javascript or a meta refresh trying to navigate away and instead
                // treat that as an outlink.
                navigator.setNavigationHandler(this::handleNavigation);

                log.info("Nav to {}", frontierUrl.url());
                var navigation = navigator.navigateTo(frontierUrl.url());
                navigation.loadEvent().get(120, TimeUnit.SECONDS);
                log.info("Load event");

                Thread.sleep(200);

                try {
                    navigator.scrollToBottom();
                } catch (CDPException e) {
                    if (!e.getMessage().contains("uniqueContextId not found") &&
                        !e.getMessage().contains("Execution context was destroyed.")) {
                        throw e;
                    }
                }

                navigator.waitForRequestInterceptorIdle();

                try {
                    List<Url> links = navigator.extractLinks();
                    if (log.isTraceEnabled()) {
                        for (var link : links) {
                            log.trace("Link: {}", link);
                        }
                    }
                    frontier.addUrls(links, frontierUrl.depth() + 1, frontierUrl.url());
                    for (Url link : links) {
                        outlinks.add(link.toString() + " L a/@href");
                    }
                } catch (CDPException e) {
                    if (!e.getMessage().contains("uniqueContextId not found")) {
                        throw e;
                    }
                }

                // Prepare WARC metadata record
                var visitTimeMs = (System.nanoTime() - startTime) / 1_000_000;
                var metadata = new TreeMap<String, List<String>>();
                metadata.put("outlink", new ArrayList<>(outlinks));
                if (frontierUrl.via() != null) metadata.put("via", List.of(frontierUrl.via().toString()));
                metadata.put("visitTimeMs", List.of(String.valueOf(visitTimeMs)));

                // Save the main resource
                Long mainResourceId = null;
                try {
                    var mainResource = navigation.mainResource().get(5, TimeUnit.SECONDS);
                    mainResourceId = storage.save(pageId, mainResource, metadata);
                } catch (TimeoutException ignored) {
                    log.atWarn().addKeyValue("url", frontierUrl.url())
                            .addKeyValue("pageId", pageId)
                            .log("No main resource captured");
                }

                // Update the database
                db.pages().finish(pageId, navigator.title(), visitTimeMs, mainResourceId);
                frontier.release(frontierUrl, FrontierUrl.State.CRAWLED);
            } catch (NavigationException e) {
                log.error("NavigationException {}", e.getMessage());
                db.pages().error(pageId, e);
                synchronized (frontier) {
                    frontier.release(frontierUrl, FrontierUrl.State.FAILED);
                }
            } catch (Throwable e) {
                db.pages().error(pageId, e);
                if (closed) return;
                synchronized (frontier) {
                    frontier.release(frontierUrl, FrontierUrl.State.FAILED);
                }
                throw e;
            } finally {
                log.info("Finished worker {} for {} [{}]", id, frontierUrl.url(), pageId);
                updateInfo(new Info(id, null, null, Instant.now()));
            }

            navigator.close();
            navigator = null;
            outlinks.clear();
        }
    }

    /**
     * Called when the page itself initiates navigation (e.g. due to a script or refresh meta tag).
     * We prevent navigation by returning false and instead add the url as an outlink.
     */
    private boolean handleNavigation(Url url, Page.ClientNavigationReason reason) {
        String linkContext = switch (reason) {
            case anchorClick -> "a@href";
            case metaTagRefresh -> "meta";
            case httpHeaderRefresh -> "=HEADER_MISC";
            case scriptInitiated, reload -> "=JS_MISC";
            case formSubmissionGet, formSubmissionPost -> "form";
            default -> "=OTHER_MISC";
        };
        String hop = switch (reason) {
            case anchorClick -> "L";
            case metaTagRefresh, httpHeaderRefresh, scriptInitiated -> "R";
            case formSubmissionGet, formSubmissionPost -> "S";
            default -> "X";
        };
        frontier.addUrls(List.of(url), frontierUrl.depth() + 1, frontierUrl.url());
        outlinks.add(url + " " + hop + " " + linkContext);
        return false;
    }

    private void updateInfo(Info info) {
        this.info = info;
    }

    public synchronized void start() {
        log.info("Starting worker {}", id);
        thread = new Thread(() -> {
            try {
                run();
            } catch (Exception e) {
                log.error("Worker crashed", e);
            }
        }, "Worker-" + id);
        thread.start();
    }

    public record Info(
            String id,
            Long pageId,
            Url url,
            Instant updateTime) {
    }

    public Info info() {
        return info;
    }
}
