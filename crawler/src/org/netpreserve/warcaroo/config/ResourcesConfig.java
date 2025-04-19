package org.netpreserve.warcaroo.config;

import org.netpreserve.warcaroo.UrlMatcher;

import java.util.List;

public record ResourcesConfig(
        List<UrlMatcher> include,
        List<UrlMatcher> exclude
) {
}
