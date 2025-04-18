package org.netpreserve.warcaroo.config;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.netpreserve.warcaroo.util.ByteSizeDeserializer;

import java.time.Duration;

/**
 * Per host/domain limits.
 *
 * @param pages maximum number of pages to fetch
 * @param bytes maximum number of bytes to download
 */
public record LocalLimitsConfig(
        Long pages,
        @JsonDeserialize(using = ByteSizeDeserializer.class)
        Long bytes
) {
}
