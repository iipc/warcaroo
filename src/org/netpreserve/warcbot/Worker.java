package org.netpreserve.warcbot;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import org.netpreserve.warcbot.cdp.BrowserProcess;
import org.netpreserve.warcbot.cdp.Navigator;
import org.netpreserve.warcbot.util.Url;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

public class Worker {
    private static final Logger log = LoggerFactory.getLogger(Worker.class);
    final int id;
    Navigator navigator;
    private final BrowserProcess browserProcess;
    private final Frontier frontier;
    private final Storage storage;
    private final Database database;
    private final NoArgGenerator pageIdGenerator = Generators.timeBasedEpochGenerator();
    private final RobotsTxtChecker robotsTxtChecker;
    private final Tracker tracker;
    private final Config config;
    private Thread thread;
    private volatile boolean closed = false;
    private volatile UUID pageId;

    public Worker(int id, BrowserProcess browserProcess, Frontier frontier, Storage storage, Database database, RobotsTxtChecker robotsTxtChecker, Tracker tracker, Config config) {
        this.id = id;
        this.browserProcess = browserProcess;
        this.frontier = frontier;
        this.storage = storage;
        this.database = database;
        this.robotsTxtChecker = robotsTxtChecker;
        this.tracker = tracker;
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
            var candidate = frontier.next(id);
            if (candidate == null) {
                log.info("No work available for worker {}", id);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }

            pageId = pageIdGenerator.generate();
            var startTime = System.nanoTime();

            log.info("Running worker for {} [{}]", candidate, pageId);

            try {
                if (!robotsTxtChecker.checkAllowed(pageId, candidate.url())) {
                    frontier.markRobotsExcluded(candidate);
                    continue;
                }

                if (navigator == null) {
                    navigator = browserProcess.newWindow(this::handleResource, null, tracker);
                }
                navigator.setUserAgent(config.getUserAgent());

                //try (var ignored = browser.recordResources(storage, pageId)) {
                log.info("Nav to {}", candidate.url());
                navigator.navigateTo(candidate.url());
                navigator.waitForLoadEvent();
                log.info("Load event");

                Thread.sleep(200);

                log.info("Force lazy");
                navigator.forceLoadLazyImages();

                log.info("Scrolling");
                navigator.scrollToBottom();

                navigator.waitForRequestInterceptorIdle();

                List<Url> links = navigator.extractLinks();
                if (log.isTraceEnabled()) {
                    for (var link : links) {
                        log.trace("Link: {}", link);
                    }
                }
                frontier.addUrls(links, candidate.depth() + 1, candidate.url());

                var visitTimeMs = (System.nanoTime() - startTime) / 1_000_000;
                storage.dao.addPage(pageId, navigator.currentUrl(), Instant.now(), navigator.title(),
                        visitTimeMs);

                navigator.navigateToBlank();
            } catch (Throwable e) {
                if (closed) return;
                frontier.markFailed(candidate, pageId, e);
                throw e;
            } finally {
                log.info("Finished worker {} for {} [{}]", id, candidate.url(), pageId);
            }
            frontier.markCrawled(candidate);
        }
    }

    public synchronized void start() {
        log.info("Starting worker {}", id);
        thread = new Thread(() -> {
            try {
                run();
            } catch (Exception e) {
                log.error("Worker error", e);
            }
        }, "Worker-" + id);
        thread.start();
    }
}
