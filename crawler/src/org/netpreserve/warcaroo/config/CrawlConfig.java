package org.netpreserve.warcaroo.config;


import org.jetbrains.annotations.Nullable;

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
        int delay) {
}