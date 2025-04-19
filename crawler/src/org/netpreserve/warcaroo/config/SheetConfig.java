package org.netpreserve.warcaroo.config;

import org.netpreserve.warcaroo.UrlMatcher;

import java.util.List;

public record SheetConfig(
        String name,
        List<UrlMatcher> matches
) {
}
