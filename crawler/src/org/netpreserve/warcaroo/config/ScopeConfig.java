package org.netpreserve.warcaroo.config;

import java.util.List;

/**
 * Crawl scope configuration.
 *
 * @param include rules for URLs to include
 * @param exclude rules for URLs to exclude (overrides includes)
 */
public record ScopeConfig(
        List<MatchRule> include,
        List<MatchRule> exclude
) {
}
