package org.netpreserve.warcbot;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.function.Predicate;

public class Frontier {
    private static final Logger log = LoggerFactory.getLogger(Frontier.class);
    private final FrontierDAO dao;
    private final Predicate<String> scope;

    public Frontier(FrontierDAO dao, Predicate<String> scope) {
        this.dao = dao;
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
        dao.queuesInsert(queue);

        var candidate = new Candidate(queue, url, depth, via, Instant.now(), Candidate.State.PENDING);
        boolean didAdd = dao.addCandidate(candidate);
        if (didAdd) {
            dao.incrementQueueCount(queue);
        }
        return true;
    }

    public @Nullable Candidate next(int workerId) throws SQLException {
        String queue = dao.takeNextQueue(workerId);
        if (queue == null) return null;
        return dao.takeNextUrlFromQueue(queue);
    }

    private String queueForUrl(Url url) {
        return url.host();
    }

    public void markFailed(Candidate candidate) throws SQLException {
        dao.setCandidateState(candidate.url(), Candidate.State.FAILED);
        dao.releaseQueue(candidate.queue(), Instant.now());
    }

    public void markCrawled(Candidate candidate) throws SQLException {
        dao.setCandidateState(candidate.url(), Candidate.State.CRAWLED);
        dao.releaseQueue(candidate.queue(), Instant.now());
    }

    public void markRobotsExcluded(Candidate candidate) throws SQLException {
        dao.setCandidateState(candidate.url(), Candidate.State.ROBOTS_EXCLUDED);
        dao.releaseQueue(candidate.queue(), Instant.now());
    }
}
