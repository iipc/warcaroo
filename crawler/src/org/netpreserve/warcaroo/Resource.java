package org.netpreserve.warcaroo;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.netpreserve.jwarc.WarcDigest;
import org.netpreserve.warcaroo.cdp.domains.Network;
import org.netpreserve.warcaroo.util.BareMediaType;
import org.netpreserve.warcaroo.util.Url;

import java.time.Instant;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record Resource(
        Long id,
        @NotNull UUID responseUuid,
        @NotNull long pageId,
        @NotNull String method,
        @NotNull Url url,
        long hostId,
        long domainId,
        @NotNull Instant date,
        String filename,
        long responseOffset,
        long responseLength,
        long requestLength,
        long metadataLength,
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
        return responseLength + requestLength + metadataLength;
    }
    public record Metadata(long pageId, long fetchTimeMs, String ipAddress) {
    }

    public Resource withId(Long id) {
        return new Resource(id, responseUuid, pageId, method, url, hostId, domainId,
                date, filename, responseOffset, responseLength, requestLength, metadataLength,
                status, redirect, payloadType, payloadSize, payloadDigest, fetchTimeMs,
                ipAddress, type, protocol, transferred);
    }
}
