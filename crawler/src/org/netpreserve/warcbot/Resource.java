package org.netpreserve.warcbot;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.netpreserve.jwarc.WarcDigest;

import java.time.Instant;
import java.util.UUID;

public record Resource(@NotNull UUID id, @NotNull UUID pageId, String method, @NotNull String url, @NotNull Instant date,
                       String filename, long responseOffset, long responseLength, long requestLength, int status,
                       @Nullable String redirect,
                       String payloadType, long payloadSize, WarcDigest payloadDigest,
                       long fetchTimeMs, String ipAddress, String type, String protocol) {
    public record Metadata(UUID pageId, long fetchTimeMs, String ipAddress) {
    }
}
