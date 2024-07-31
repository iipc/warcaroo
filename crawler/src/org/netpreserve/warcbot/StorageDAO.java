package org.netpreserve.warcbot;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.jdbi.v3.core.mapper.Nested;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.*;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.netpreserve.warcbot.util.MustUpdate;
import org.netpreserve.warcbot.util.Url;
import org.netpreserve.warcbot.webapp.Webapp;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@RegisterConstructorMapper(Resource.class)
public interface StorageDAO {
    @SqlQuery("INSERT INTO hosts (rhost) VALUES (:rhost) ON CONFLICT (rhost) DO UPDATE SET rhost = excluded.rhost RETURNING id")
    long insertOrGetHostId(String rhost);

    @SqlQuery("INSERT INTO domains (rhost) VALUES (:rhost) ON CONFLICT (rhost) DO UPDATE SET rhost = excluded.rhost RETURNING id")
    long insertOrGetDomainId(String rhost);

    @Transaction
    default void addResource(@BindMethods Resource resource) {
        _addResource(resource);
        _addResourceToHost(resource);
        _addResourceToDomain(resource);
    }

    @SqlUpdate("""
            INSERT INTO resources (id, page_id, method, url, host_id, domain_id, date, filename, response_offset, response_length, request_length,
               status, redirect, payload_type, payload_size, payload_digest, fetch_time_ms, ip_address, type,
               protocol, transferred)
            VALUES (:id, :pageId, :method, :url, :hostId, :domainId, :date, :filename, :responseOffset, :responseLength, :requestLength,
                    :status, :redirect, :payloadType, :payloadSize, :payloadDigest, :fetchTimeMs, :ipAddress, :type,
                    :protocol, :transferred)""")
    void _addResource(@BindMethods Resource resource);

    @SqlUpdate("""
            UPDATE hosts
            SET resources = resources + 1,
                size = size + :payloadSize,
                transferred = transferred + :transferred,
                storage = storage + :storage
            WHERE id = :hostId""")
    void _addResourceToHost(@BindMethods Resource resource);

    @SqlUpdate("""
            UPDATE domains
            SET resources = resources + 1,
                size = size + :payloadSize,
                transferred = transferred + :transferred,
                storage = storage + :storage
            WHERE id = :domainId""")
    void _addResourceToDomain(@BindMethods Resource resource);

    default void addPage(@BindMethods Page page) {
        _addPage(page);
        _addPageToHost(page);
        _addPageToDomain(page);
    }

    @SqlUpdate("INSERT INTO pages (id, url, date, title, visit_time_ms, host_id, domain_id) " +
               "VALUES (:id, :url, :date, :title, :visitTimeMs, :hostId, :domainId)")
    void _addPage(@BindMethods Page page);

    @SqlUpdate("UPDATE hosts SET pages = pages + 1 WHERE id = :hostId")
    @MustUpdate
    void _addPageToHost(@BindMethods Page page);

    @SqlUpdate("UPDATE domains SET pages = pages + 1 WHERE id = :hostId")
    @MustUpdate
    void _addPageToDomain(@BindMethods Page page);

    String PAGES_WHERE = " WHERE (:hostId IS NULL OR host_id = :hostId) ";

    @SqlQuery("SELECT COUNT(*) FROM pages " + PAGES_WHERE)
    long countPages(@BindFields Webapp.PagesQuery query);

    @RegisterConstructorMapper(Page.class)
    @SqlQuery("""
            SELECT *
            FROM pages
            """ + PAGES_WHERE + """
            <orderBy> LIMIT :limit OFFSET :offset""")
    List<Page> queryPages(@BindFields Webapp.PagesQuery query, @Define String orderBy, int limit, long offset);

    String RESOURCES_WHERE = """
          WHERE (:hostId IS NULL OR host_id = :hostId)
            AND (:url IS NULL OR url GLOB :url)
            AND (:pageId IS NULL OR page_id GLOB :pageId)
          """;

    @SqlQuery("SELECT COUNT(*) FROM resources\n" + RESOURCES_WHERE)
    long countResources(@BindFields Webapp.ResourcesQuery query);

    @SqlQuery("""
            SELECT *
            FROM resources
            """ + RESOURCES_WHERE + """
            <orderBy> LIMIT :limit OFFSET (:page - 1) * :limit""")
    @DefineNamedBindings
    List<Resource> queryResources(@Define String orderBy, @BindFields Webapp.ResourcesQuery query);

    record Page(UUID id, Url url, Instant date, String title, long visitTimeMs, long hostId, long domainId) {
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
    Resource findResourceByUrl(Url uri);

    record Host(long id, String rhost, Instant lastVisit, Instant nextVisit, long seeds, long pending, long failed,
                long robotsExcluded, long pages, long resources, long size, long transferred, long storage) {
        @JsonProperty
        public String host() {
            if (rhost.contains(",")) {
                var segments = Arrays.asList(rhost.split(","));
                Collections.reverse(segments);
                return String.join(".", segments);
            } else {
                return rhost;
            }
        }
    }

    String HOSTS_WHERE = """
            WHERE (:rhost IS NULL OR rhost GLOB :rhost)
            """;

    @SqlQuery("SELECT COUNT(*) FROM hosts " + HOSTS_WHERE)
    long countHosts(@BindFields Webapp.HostsQuery query);

    @SqlQuery("""
            SELECT * FROM hosts
            """ + HOSTS_WHERE + """
            <orderBy> LIMIT :limit OFFSET (:page - 1) * :limit""")
    @DefineNamedBindings
    @RegisterConstructorMapper(StorageDAO.Host.class)
    List<Host> queryHosts(@Define String orderBy, @BindFields Webapp.HostsQuery query);


}