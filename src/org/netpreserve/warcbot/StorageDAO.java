package org.netpreserve.warcbot;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

@RegisterConstructorMapper(Resource.class)
public interface StorageDAO {
    @SqlUpdate("""
            INSERT INTO resources (id, page_id, url, date, response_offset, response_length, request_length,
             status, redirect, payload_type, payload_size, payload_digest)
            VALUES (:id, :pageId, :url, :date, :responseOffset, :responseLength, :requestLength,
                    :status, :redirect, :payloadType, :payloadSize, :payloadDigest)""")
    void addResource(@BindMethods Resource resource);

    @SqlUpdate("INSERT INTO pages (id, url, date, title) VALUES (?, ?, ?, ?)")
    void addPage(@NotNull UUID id, @NotNull Url url, @NotNull Instant date, String title);
}