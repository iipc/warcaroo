package org.netpreserve.warcaroo;

import java.time.Duration;
import java.util.function.Predicate;

public record CrawlSettings(
        String userAgent,
        boolean headless,
        Long pageLimit,
        Long sizeLimit,
        Duration timeLimit,
        Integer depth,
        Long domainPageLimit,
        Long domainSizeLimit,
        Long hostPageLimit,
        Long hostSizeLimit,
        String warcPrefix
) {
    public CrawlSettings {
        validate("userAgent", userAgent, u -> u != null && !u.trim().isEmpty());
        validateIfNotNull("pageLimit", pageLimit, l -> l > 0);
        validateIfNotNull("sizeLimit", sizeLimit, l -> l > 0);
        validateIfNotNull("timeLimit", timeLimit, t -> !t.isNegative());
        validateIfNotNull("depth", depth, d -> d >= 0);
        validateIfNotNull("domainPageLimit", domainPageLimit, l -> l > 0);
        validateIfNotNull("domainSizeLimit", domainSizeLimit, l -> l > 0);
        validateIfNotNull("hostPageLimit", hostPageLimit, l -> l > 0);
        validateIfNotNull("hostSizeLimit", hostSizeLimit, l -> l > 0);
        validateIfNotNull("warcPrefix", warcPrefix, s -> !s.contains("/"));
    }

    private static <T> void validate(String name, T value, Predicate<T> validator) {
        if (!validator.test(value)) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }

    private static <T> void validateIfNotNull(String name, T value, Predicate<T> validator) {
        if (value != null && !validator.test(value)) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }

    public static final CrawlSettings DEFAULTS = new CrawlSettings("warcaroo", true,
            null, null, null, null, null, null,
            null, null, null);
}
