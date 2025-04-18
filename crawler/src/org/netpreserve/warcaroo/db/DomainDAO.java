package org.netpreserve.warcaroo.db;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.netpreserve.warcaroo.Domain;
import org.netpreserve.warcaroo.FrontierUrl;
import org.netpreserve.warcaroo.webapp.Webapp;

@RegisterConstructorMapper(Domain.class)
public interface DomainDAO {
    @SqlQuery("INSERT INTO domains (rhost) VALUES (:rhost) ON CONFLICT (rhost) DO UPDATE SET rhost = excluded.rhost RETURNING id")
    long insertOrGetId(String rhost);

    @SqlUpdate("UPDATE domains SET pending = pending + 1 WHERE id = ?")
    void incrementPending(long hostId);

    @SqlUpdate("""
            UPDATE domains
            SET pending = pending + iif(:newState = 'PENDING', 1, 0) - iif(:oldState = 'PENDING', 1, 0),
                failed = failed + iif(:newState = 'FAILED', 1, 0) - iif(:oldState = 'FAILED', 1, 0),
                robots_excluded = robots_excluded + iif(:newState = 'ROBOTS_EXCLUDED', 1, 0) - iif(:oldState = 'ROBOTS_EXCLUDED', 1, 0)
            WHERE id = :domainId
            """)
    void updateMetricsOnFrontierUrlStateChange(long domainId, FrontierUrl.State oldState, FrontierUrl.State newState);

    @SqlQuery("SELECT * FROM domains WHERE id = ?")
    Domain find(long domainId);

    @SqlQuery("SELECT * FROM domains WHERE rhost = ?")
    Domain findByRHost(String rhost);

    String HOSTS_WHERE = """
            WHERE (:rhost IS NULL OR rhost GLOB :rhost)
            """;

    @SqlQuery("SELECT COUNT(*) FROM domains " + HOSTS_WHERE)
    long count(@BindFields Webapp.HostsQuery query);
}
