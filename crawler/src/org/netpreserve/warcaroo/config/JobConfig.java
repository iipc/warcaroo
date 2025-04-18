package org.netpreserve.warcaroo.config;

import java.util.List;

/**
 * Root configuration for crawl job.
 *
 * @param scope     what to crawl (seeds, inclusion/exclusion patterns)
 * @param crawl     how to crawl (behavior, limits)
 * @param browsers  what to crawl with (local/remote browsers)
 * @param sheets    override sheets for pages matching certain criteria
 */
public record JobConfig(
        List<String> seeds,
        ScopeConfig scope,
        ResourcesConfig resources,
        CrawlConfig crawl,
        StorageConfig storage,
        List<BrowserConfig> browsers,
        List<SheetConfig> sheets
) {
}
