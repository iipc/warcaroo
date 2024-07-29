package org.netpreserve.warcbot;

import java.time.Duration;

public record CrawlSettings(
        int workers,
        String userAgent,
        boolean headless,
        Long pageLimit,
        Long sizeLimit,
        Duration timeLimit,
        Integer depth,
        Long domainPageLimit,
        Long domainSizeLimit,
        Long hostPageLimit,
        Long hostSizeLimit
) {
    public static final CrawlSettings DEFAULTS = new CrawlSettings(1, "warcbot", true,
            null, null, null, null, null, null,
            null, null);
}
