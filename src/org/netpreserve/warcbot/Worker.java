package org.netpreserve.warcbot;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import org.openqa.selenium.StaleElementReferenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;

public class Worker {
    private static final Logger log = LoggerFactory.getLogger(Worker.class);
    private final Browser browser;
    private final Frontier frontier;
    private final Storage storage;
    private final Database database;
    private final NoArgGenerator pageIdGenerator = Generators.timeBasedEpochGenerator();
    private final RobotsTxtChecker robotsTxtChecker;

    public Worker(Browser browser, Frontier frontier, Storage storage, Database database, RobotsTxtChecker robotsTxtChecker) {
        this.browser = browser;
        this.frontier = frontier;
        this.storage = storage;
        this.database = database;
        this.robotsTxtChecker = robotsTxtChecker;
    }

    void run() throws SQLException, IOException {
        while (true) {
            var candidate = frontier.next();
            if (candidate == null) break;

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
                        System.err.println("LINK: " + link);
                        Url url = new Url(link);
                        frontier.addUrl(url, candidate.depth() + 1, candidate.url());
                    }

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                    database.insertPage(pageId, browser.currentUrl(), Instant.now(), browser.title());

                    browser.navigateToBlank();
                }
            } catch (Throwable e) {
                frontier.markFailed(candidate);
                throw e;
            }
            frontier.markCrawled(candidate);
        }
    }
}
