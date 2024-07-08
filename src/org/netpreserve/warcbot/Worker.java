package org.netpreserve.warcbot;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import org.openqa.selenium.remote.UnreachableBrowserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class Worker {
    private static final Logger log = LoggerFactory.getLogger(Worker.class);
    final int id;
    final Browser browser;
    private final Frontier frontier;
    private final Storage storage;
    private final Database database;
    private final NoArgGenerator pageIdGenerator = Generators.timeBasedEpochGenerator();
    private final RobotsTxtChecker robotsTxtChecker;
    private Thread thread;
    private volatile boolean closed = false;

    public Worker(int id, Browser browser, Frontier frontier, Storage storage, Database database, RobotsTxtChecker robotsTxtChecker) {
        this.id = id;
        this.browser = browser;
        this.frontier = frontier;
        this.storage = storage;
        this.database = database;
        this.robotsTxtChecker = robotsTxtChecker;
    }

    void closeAsync() {
        if (closed) return;
        closed = true;
        thread.interrupt();
    }

    void close() {
        closeAsync();
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            // OK
        }
        try {
            browser.close();
        } catch (Exception e) {
            log.warn("browser close", e);
        }
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for thread to close", e);
        }
    }

    void run() throws SQLException, IOException {
        while (!closed) {
            var candidate = frontier.next(id);
            if (candidate == null) {
                log.info("No work available for worker {}", id);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                continue;
            }

            var pageId = pageIdGenerator.generate();

            log.info("Running worker for {} [{}]", candidate, pageId);

            try {
                if (!robotsTxtChecker.checkAllowed(pageId, candidate.url())) {
                    frontier.markRobotsExcluded(candidate);
                    continue;
                }

                try (var ignored = browser.recordResources(storage, pageId)) {
                    browser.navigateTo(candidate.url());

                    for (var link : browser.extractLinks()) {
                        log.debug("Link {}", link);
                        Url url = new Url(link);
                        frontier.addUrl(url, candidate.depth() + 1, candidate.url());
                    }

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                    storage.dao.addPage(pageId, browser.currentUrl(), Instant.now(), browser.title());

                    browser.navigateToBlank();
                }
            } catch (Throwable e) {
                if (closed) return;
                frontier.markFailed(candidate, pageId, e);
                throw e;
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
        }, "worker-" + id);
        thread.start();
    }
}
