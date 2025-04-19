package org.netpreserve.warcaroo.config;

import org.netpreserve.warcaroo.UrlMatcher;
import org.netpreserve.warcaroo.util.Url;

public record SeedConfig(
        Url url,
        ScopeType scopeType) {
    public SeedConfig(String url) {
        this(new Url(url), ScopeType.PREFIX);
    }

    public UrlMatcher toUrlMatcher() {
        return switch (scopeType()) {
            case HOST -> new UrlMatcher.Host(url().host());
            case DOMAIN -> new UrlMatcher.Domain(url().host());
            case PAGE -> new UrlMatcher.Exact(url());
            case PREFIX -> new UrlMatcher.Prefix(url().directoryPrefix());
        };
    }
}
