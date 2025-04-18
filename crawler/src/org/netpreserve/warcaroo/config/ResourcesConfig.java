package org.netpreserve.warcaroo.config;

import java.util.List;

public record ResourcesConfig(
        List<MatchRule> include,
        List<MatchRule> exclude
) {
}
