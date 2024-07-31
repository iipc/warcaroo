package org.netpreserve.warcbot;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import org.netpreserve.warcbot.cdp.*;
import org.netpreserve.warcbot.cdp.protocol.CDPException;
import org.netpreserve.warcbot.util.Url;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class Worker {
    private static final Logger log = LoggerFactory.getLogger(Worker.class);
    final int id;
    Navigator navigator;
    private final BrowserProcess browserProcess;
    private final Frontier frontier;
    private final Storage storage;
    private final Database db;
    private final NoArgGenerator pageIdGenerator = Generators.timeBasedEpochGenerator();
    private final RobotsTxtChecker robotsTxtChecker;
    private final Config config;
    private Thread thread;
    private volatile boolean closed = false;
    private volatile Long pageId;

    public Worker(int id, BrowserProcess browserProcess, Frontier frontier, Storage storage, Database db, RobotsTxtChecker robotsTxtChecker, Config config) {
        this.id = id;
        this.browserProcess = browserProcess;
        this.frontier = frontier;
        this.storage = storage;
        this.db = db;
        this.robotsTxtChecker = robotsTxtChecker;
        this.config = config;
    }

    private void handleResource(ResourceFetched resource) {
        if (pageId == null) return;
        try {
            storage.save(pageId, resource);
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

    void run() throws SQLException, IOException, InterruptedException, TimeoutException, NavigationException {
        while (!closed) {
            var candidate = frontier.takeNext(id);
            if (candidate == null) {
                log.info("No work available for worker {}", id);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }

            pageId = db.pages().create(candidate.url(), candidate.hostId(), candidate.domainId(), Instant.now());

            var startTime = System.nanoTime();

            log.atInfo().addKeyValue("pageId", pageId).addKeyValue("url", candidate.url()).log("Considering page");

            try {
                if (!robotsTxtChecker.checkAllowed(pageId, candidate.url())) {
                    frontier.release(candidate, FrontierUrl.State.ROBOTS_EXCLUDED);
                    continue;
                }

                if (navigator == null) {
                    navigator = browserProcess.newWindow(this::handleResource, null);
                }
                navigator.setUserAgent(config.getCrawlSettings().userAgent());
                navigator.block(config.getBlockPredicate());

                log.info("Nav to {}", candidate.url());
                navigator.navigateTo(candidate.url());
                navigator.waitForLoadEvent();
                log.info("Load event");

                Thread.sleep(200);

                try {
                    navigator.forceLoadLazyImages();
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
                    frontier.addUrls(links, candidate.depth() + 1, candidate.url());
                } catch (CDPException e) {
                    if (!e.getMessage().contains("uniqueContextId not found")) {
                        throw e;
                    }
                }

                var visitTimeMs = (System.nanoTime() - startTime) / 1_000_000;

                db.pages().finish(pageId, navigator.title(), visitTimeMs);
                frontier.release(candidate, FrontierUrl.State.CRAWLED);
            } catch (NavigationException e) {
                log.error("NavigationException {}", e.getMessage());
                db.pages().error(pageId, e);
                synchronized (frontier) {
                    frontier.release(candidate, FrontierUrl.State.FAILED);
                }
            } catch (Throwable e) {
                db.pages().error(pageId, e);
                if (closed) return;
                synchronized (frontier) {
                    frontier.release(candidate, FrontierUrl.State.FAILED);
                }
                throw e;
            } finally {
                log.info("Finished worker {} for {} [{}]", id, candidate.url(), pageId);
            }

            navigator.navigateToBlank();
        }
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
}
