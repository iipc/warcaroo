package org.netpreserve.warcbot;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.*;
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

    public void addUrl(Url url, int depth, Url via) {
        addUrls(Collections.singleton(url), depth, via);
    }

    public void addUrls(Collection<Url> urls, int depth, Url via) {
        var queueNames = new HashSet<String>();
        var candidates = new ArrayList<Candidate>();
        for (var url : urls) {
            if (!url.isHttp()) continue;
            url = url.withoutFragment();
            if (!scope.test(url.toString())) continue;
            String queue = queueForUrl(url);
            if (queue == null) {
                log.warn("No queue for URL {}", url);
                return;
            }
            queueNames.add(queue);
            candidates.add(new Candidate(queue, url, depth, via, Instant.now(), Candidate.State.PENDING));
        }

        dao.addQueues(queueNames);
        boolean[] result = dao.addCandidates(candidates);
        int novel = 0;
        for (int i = 0; i < result.length; i++) {
            if (result[i]) {
                novel++;
                log.debug("Added {}", candidates.get(i));
            }
        }
        log.info("Added {} new URLs from {} extracted links", novel, candidates.size());
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
