package org.netpreserve.warcbot;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

@RegisterConstructorMapper(Resource.class)
public interface StorageDAO {
    @SqlUpdate("""
            INSERT INTO resources (id, page_id, url, date, response_offset, response_length, request_length,
             status, redirect, payload_type, payload_size, payload_digest, fetch_time_ms, ip_address)
            VALUES (:id, :pageId, :url, :date, :responseOffset, :responseLength, :requestLength,
                    :status, :redirect, :payloadType, :payloadSize, :payloadDigest, :fetchTimeMs, :ipAddress)""")
    void addResource(@BindMethods Resource resource);

    @SqlUpdate("INSERT INTO pages (id, url, date, title) VALUES (?, ?, ?, ?)")
    void addPage(@NotNull UUID id, @NotNull Url url, @NotNull Instant date, String title);

    @SqlQuery("""
            SELECT * FROM resources
            WHERE url = :uri
            AND payload_size = :payloadSize
            AND payload_digest = :payloadDigest
            LIMIT 1""")
    Resource findResourceByUrlAndPayload(String uri, long payloadSize, String payloadDigest);
}