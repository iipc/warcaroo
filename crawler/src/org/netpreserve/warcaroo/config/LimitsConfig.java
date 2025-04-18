package org.netpreserve.warcaroo.config;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.netpreserve.warcaroo.util.ByteSizeDeserializer;
import org.netpreserve.warcaroo.util.DurationDeserializer;

import java.time.Duration;

/**
 * Global crawl limits.
 *
 * @param pages maximum number of pages to fetch
 * @param bytes maximum number of bytes to download
 * @param time maximum duration to run the crawl for
 */
public record LimitsConfig(
        Long pages,
        @JsonDeserialize(using = ByteSizeDeserializer.class)
        Long bytes,
        @JsonDeserialize(using = DurationDeserializer.class)
        Duration time
) {
}
