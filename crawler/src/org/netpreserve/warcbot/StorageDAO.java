package org.netpreserve.warcbot;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.jdbi.v3.core.mapper.Nested;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.customizer.DefineNamedBindings;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jetbrains.annotations.NotNull;
import org.netpreserve.warcbot.util.Url;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RegisterConstructorMapper(Resource.class)
public interface StorageDAO {
    @SqlUpdate("""
            INSERT INTO resources (id, page_id, method, url, date, filename, response_offset, response_length, request_length,
               status, redirect, payload_type, payload_size, payload_digest, fetch_time_ms, ip_address, type,
               protocol)
            VALUES (:id, :pageId, :method, :url, :date, :filename, :responseOffset, :responseLength, :requestLength,
                    :status, :redirect, :payloadType, :payloadSize, :payloadDigest, :fetchTimeMs, :ipAddress, :type,
                    :protocol)""")
    void addResource(@BindMethods Resource resource);

    @SqlUpdate("INSERT INTO pages (id, url, date, title, visit_time_ms) VALUES (?, ?, ?, ?, ?)")
    void addPage(@NotNull UUID id, @NotNull Url url, @NotNull Instant date, String title, long visitTimeMs);

    @SqlQuery("SELECT COUNT(*) FROM pages")
    long countPages();

    @RegisterConstructorMapper(Page.class)
    @RegisterConstructorMapper(PageExt.class)
    @SqlQuery("""
            SELECT pages.*,
              COUNT(resources.id) AS resources,
              SUM(resources.payload_size) AS size
            FROM pages
            LEFT JOIN resources ON pages.id = resources.page_id
            GROUP BY pages.id <orderBy> LIMIT :limit OFFSET :offset""")
    List<PageExt> queryPages(@Define String orderBy, int limit, long offset);

    @SqlQuery("""
         SELECT COUNT(*) FROM resources
         WHERE (:url IS NULL OR url GLOB :url)
           AND (:pageId IS NULL OR page_id GLOB :pageId)
         """)
    long countResources(String url, String pageId);

    @SqlQuery("""
            SELECT *
            FROM resources
            WHERE (:url IS NULL OR url GLOB :url)
              AND (:pageId IS NULL OR page_id GLOB :pageId)
            <orderBy> LIMIT :limit OFFSET :offset""")
    @DefineNamedBindings
    List<Resource> queryResources(@Define String orderBy, String url, String pageId, int limit, long offset);

    record ResourceFilters(String url, String pageId) {
    }

    record Page(String id, String url, Instant date, String title, long visitTimeMs) {
    }

    record PageExt(@Nested @JsonUnwrapped Page page, long resources, long size) {
    }


    @SqlQuery("""
            SELECT * FROM resources
            WHERE url = :uri
            AND payload_size = :payloadSize
            AND payload_digest = :payloadDigest
            LIMIT 1""")
    Resource findResourceByUrlAndPayload(String uri, long payloadSize, String payloadDigest);

    @SqlQuery("""
            SELECT * FROM resources
            WHERE url = :uri
            ORDER BY date DESC
            LIMIT 1""")
    Resource findResourceByUrl(String uri);

}