package org.netpreserve.warcbot;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;

public record FrontierUrl(
        @NotNull String queue,
        @NotNull Url url,
        int depth,
        Url via,
        Instant timeAdded,
        Status status
) {
    public enum Status {
        PENDING, IN_PROGRESS, CRAWLED, FAILED, ROBOTS_EXCLUDED;
    }
}
