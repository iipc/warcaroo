package org.netpreserve.warcbot;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.function.Predicate;

public class Frontier {
    private static final Logger log = LoggerFactory.getLogger(Frontier.class);
    private final Database db;
    private final Predicate<String> scope;

    public Frontier(Database db, Predicate<String> scope) {
        this.db = db;
        this.scope = scope;
    }

    public boolean addUrl(Url url, int depth, Url via) throws SQLException {
        if (!url.isHttp()) return false;
        url = url.withoutFragment();
        if (!scope.test(url.toString())) return false;
        String queue = queueForUrl(url);
        if (queue == null) {
            log.warn("No queue for URL {}", url);
            return false;
        }
        db.queuesInsert(queue);
        db.frontierInsert(queue, depth, url, via, Instant.now(), FrontierUrl.Status.PENDING);
        return true;
    }

    public @Nullable FrontierUrl next() throws SQLException {
        FrontierUrl frontierUrl = db.frontierNext();
        db.frontierSetUrlStatus(frontierUrl.url(), FrontierUrl.Status.IN_PROGRESS);
        return frontierUrl;
    }

    private String queueForUrl(Url url) {
        return url.host();
    }

    public void markFailed(FrontierUrl candidate) throws SQLException {
        db.frontierSetUrlStatus(candidate.url(), FrontierUrl.Status.FAILED);
    }

    public void markCrawled(FrontierUrl candidate) throws SQLException {
        db.frontierSetUrlStatus(candidate.url(), FrontierUrl.Status.CRAWLED);
    }

    public void markRobotsExcluded(FrontierUrl candidate) throws SQLException {
        db.frontierSetUrlStatus(candidate.url(), FrontierUrl.Status.ROBOTS_EXCLUDED);
    }
}
