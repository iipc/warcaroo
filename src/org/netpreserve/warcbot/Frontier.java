package org.netpreserve.warcbot;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;

public class Frontier {
    private static final Logger log = LoggerFactory.getLogger(Frontier.class);
    private final FrontierDAO dao;
    private final Predicate<String> scope;
    private final Config config;

    public Frontier(FrontierDAO dao, Predicate<String> scope, Config config) {
        this.dao = dao;
        this.scope = scope;
        this.config = config;
    }

    public boolean addUrl(Url url, int depth, Url via) {
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
        dao.addCandidate(candidate);
        return true;
    }

    public @Nullable Candidate next(int workerId) {
        String queue = dao.takeNextQueue(workerId);
        if (queue == null) return null;
        return dao.takeNextUrlFromQueue(queue);
    }

    private String queueForUrl(Url url) {
        return url.host();
    }

    public void markFailed(Candidate candidate, UUID pageId, Throwable e) {
        dao.setCandidateState(candidate.url(), Candidate.State.FAILED);
        releaseQueueForCandidate(candidate);
        Instant now = Instant.now();
        var stringWriter = new StringWriter();
        e.printStackTrace(new PrintWriter(stringWriter));
        dao.addError(pageId, candidate.url(), now, stringWriter.toString());
    }

    public void markCrawled(Candidate candidate) {
        dao.setCandidateState(candidate.url(), Candidate.State.CRAWLED);
        releaseQueueForCandidate(candidate);
    }

    public void markPending(Candidate candidate) {
        dao.setCandidateState(candidate.url(), Candidate.State.PENDING);
        releaseQueueForCandidate(candidate);
    }

    public void markRobotsExcluded(Candidate candidate) {
        dao.setCandidateState(candidate.url(), Candidate.State.ROBOTS_EXCLUDED);
        releaseQueueForCandidate(candidate);
    }

    private void releaseQueueForCandidate(Candidate candidate) {
        Instant now = Instant.now();
        dao.releaseQueue(candidate.queue(), now, now.plusMillis(config.getCrawlDelay()));
    }
}
