package org.netpreserve.warcaroo.config;

import java.util.List;

public record SheetConfig(
        String name,
        List<MatchRule> matches
) {
}
