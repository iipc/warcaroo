package org.netpreserve.warcbot;

import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

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
}
