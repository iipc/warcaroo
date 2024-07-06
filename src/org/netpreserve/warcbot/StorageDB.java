package org.netpreserve.warcbot;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.netpreserve.jwarc.WarcDigest;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

public interface StorageDB {
    void insertResource(@NotNull UUID id, @NotNull UUID pageId, @NotNull String url, @NotNull Instant date,
                        long responseOffset, long responseLength, long requestLength, int status,
                        @Nullable String redirect,
                        String payloadType, long payloadSize, WarcDigest payloadDigest) throws SQLException;
}