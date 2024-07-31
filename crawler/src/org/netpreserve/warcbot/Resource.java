package org.netpreserve.warcbot;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.netpreserve.jwarc.WarcDigest;
import org.netpreserve.warcbot.cdp.domains.Network;
import org.netpreserve.warcbot.util.BareMediaType;
import org.netpreserve.warcbot.util.Url;

import java.time.Instant;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record Resource(
        @NotNull UUID id,
        @NotNull UUID pageId,
        @NotNull String method,
        @NotNull Url url,
        long hostId,
        long domainId,
        @NotNull Instant date,
        String filename,
        long responseOffset,
        long responseLength,
        long requestLength,
        int status,
        @Nullable String redirect,
        BareMediaType payloadType,
        long payloadSize,
        WarcDigest payloadDigest,
        long fetchTimeMs,
        String ipAddress,
        Network.ResourceType type,
        String protocol,
        long transferred) {
    public Resource {
    }

    public long storage() {
        return responseLength + requestLength;
    }
    public record Metadata(UUID pageId, long fetchTimeMs, String ipAddress) {
    }
}
