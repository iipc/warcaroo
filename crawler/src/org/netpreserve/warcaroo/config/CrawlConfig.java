package org.netpreserve.warcaroo.config;


import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * Configuration for how the crawl should behave.
 *
 * @param userAgent User-Agent string to identify as to servers
 * @param limits    global crawl limits (pages, bytes, time)
 * @param perDomain per-domain limits (pages, bytes)
 * @param perHost   per-host limits (pages, bytes)
 * @param depth     maximum link depth from any seed
 * @param delay     milliseconds to wait between requests
 */
public record CrawlConfig(
        String userAgent,
        LimitsConfig limits,
        LocalLimitsConfig perDomain,
        LocalLimitsConfig perHost,
        @Nullable Integer depth,
        @Nullable Integer delay) {
    private static final int DEFAULT_DELAY = 1000;

    public int delayOrDefault() {
        return delay == null ? DEFAULT_DELAY : delay;
    }
}
