package org.netpreserve.warcbot;

import org.jetbrains.annotations.NotNull;
import org.netpreserve.warcbot.util.Url;

import java.time.Instant;

/**
 * A candidate URL for web crawling.
 *
 * @param url       The URL to be crawled. Cannot be null.
 * @param depth     The number of links from the seed URL to this candidate URL.
 * @param via       The URL through which this candidate was discovered. Can be null if this is a seed URL.
 * @param timeAdded The timestamp when this candidate was added to the queue.
 * @param state     The current state of the candidate.
 */
public record FrontierUrl(
        long id,
        @NotNull Url url,
        long hostId,
        long domainId,
        int depth,
        Url via,
        Instant timeAdded,
        State state
) {
    public enum State {
        PENDING, CRAWLED, FAILED, ROBOTS_EXCLUDED
    }
}
