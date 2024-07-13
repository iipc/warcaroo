package org.netpreserve.warcbot;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

public class Worker {
    private static final Logger log = LoggerFactory.getLogger(Worker.class);
    final int id;
    final BrowserWindow browserWindow;
    private final Frontier frontier;
    private final Storage storage;
    private final Database database;
    private final NoArgGenerator pageIdGenerator = Generators.timeBasedEpochGenerator();
    private final RobotsTxtChecker robotsTxtChecker;
    private Thread thread;
    private volatile boolean closed = false;

    public Worker(int id, BrowserProcess browserProcess, Frontier frontier, Storage storage, Database database, RobotsTxtChecker robotsTxtChecker) {
        this.id = id;
        this.browserWindow = browserProcess.newWindow(this::handleResource);
        this.frontier = frontier;
        this.storage = storage;
        this.database = database;
        this.robotsTxtChecker = robotsTxtChecker;
    }

    private void handleResource(ResourceFetched resourceFetched) {
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
            browserWindow.close();
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

                //try (var ignored = browser.recordResources(storage, pageId)) {
                browserWindow.navigateTo(candidate.url());

                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                browserWindow.forceLoadLazyImages();
                browserWindow.scrollToBottom();

                List<Url> links = browserWindow.extractLinks();
                if (log.isDebugEnabled()) {
                    for (var link : links) {
                        log.debug("Link: {}", link);
                    }
                }
                frontier.addUrls(links, candidate.depth() + 1, candidate.url());

                storage.dao.addPage(pageId, browserWindow.currentUrl(), Instant.now(), browserWindow.title());

                browserWindow.navigateToBlank();
                //}
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
        }, "worker-" + id);
        thread.start();
    }
}
