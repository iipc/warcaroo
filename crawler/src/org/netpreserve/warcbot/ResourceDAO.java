package org.netpreserve.warcbot;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.customizer.DefineNamedBindings;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.netpreserve.warcbot.util.Url;
import org.netpreserve.warcbot.webapp.Webapp;

import java.util.List;

@RegisterConstructorMapper(Resource.class)
public interface ResourceDAO {

    @Transaction
    default void add(@BindMethods Resource resource) {
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

    String RESOURCES_WHERE = """
          WHERE (:hostId IS NULL OR host_id = :hostId)
            AND (:url IS NULL OR url GLOB :url)
            AND (:pageId IS NULL OR page_id GLOB :pageId)
          """;

    @SqlQuery("SELECT COUNT(*) FROM resources\n" + RESOURCES_WHERE)
    long count(@BindFields Webapp.ResourcesQuery query);

    @SqlQuery("""
            SELECT *
            FROM resources
            """ + RESOURCES_WHERE + """
            <orderBy> LIMIT :limit OFFSET (:page - 1) * :limit""")
    @DefineNamedBindings
    List<Resource> query(@Define String orderBy, @BindFields Webapp.ResourcesQuery query);

    @SqlQuery("""
            SELECT * FROM resources
            WHERE url = :uri
            AND payload_size = :payloadSize
            AND payload_digest = :payloadDigest
            LIMIT 1""")
    Resource findByUrlAndPayload(String uri, long payloadSize, String payloadDigest);

    @SqlQuery("""
            SELECT * FROM resources
            WHERE url = :uri
            ORDER BY date DESC
            LIMIT 1""")
    Resource findByUrl(Url uri);
}
