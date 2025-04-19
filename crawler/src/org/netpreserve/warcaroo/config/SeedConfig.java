package org.netpreserve.warcaroo.config;

import org.netpreserve.warcaroo.util.Url;

public record SeedConfig(
        Url url,
        ScopeType scopeType) {
    public SeedConfig(String url) {
        this(new Url(url), ScopeType.PREFIX);
    }
}
