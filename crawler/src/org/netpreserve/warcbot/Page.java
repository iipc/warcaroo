package org.netpreserve.warcbot;

import org.netpreserve.warcbot.util.Url;

import java.time.Instant;
import java.util.UUID;

public record Page(
        long id,
        Url url,
        Instant date,
        String title,
        long visitTimeMs,
        long hostId,
        long domainId,
        long resources,
        long size) {
}
