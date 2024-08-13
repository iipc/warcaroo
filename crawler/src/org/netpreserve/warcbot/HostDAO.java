package org.netpreserve.warcbot;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.customizer.DefineNamedBindings;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.netpreserve.warcbot.webapp.Webapp;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@RegisterConstructorMapper(Host.class)
public interface HostDAO {
    @SqlQuery("SELECT * FROM hosts WHERE id = ?")
    Host find(long id);

    @SqlQuery("INSERT INTO hosts (rhost) VALUES (:rhost) ON CONFLICT (rhost) DO UPDATE SET rhost = excluded.rhost RETURNING id")
    long insertOrGetId(String rhost);

    @SqlQuery("SELECT id FROM hosts WHERE next_visit < :now AND id NOT IN (<excluded>) ORDER BY next_visit LIMIT 1")
    Long findNextToVisit(Instant now, @BindList(value = "excluded", onEmpty = BindList.EmptyHandling.VOID) Collection<Long> excluded);

    @SqlUpdate("""
            UPDATE hosts
            SET last_visit = :now,
                next_visit = :nextVisit,
                pending = pending + iif(:newState = 'PENDING', 1, 0) - iif(:oldState = 'PENDING', 1, 0),
                failed = failed + iif(:newState = 'FAILED', 1, 0) - iif(:oldState = 'FAILED', 1, 0),
                robots_excluded = robots_excluded + iif(:newState = 'ROBOTS_EXCLUDED', 1, 0) - iif(:oldState = 'ROBOTS_EXCLUDED', 1, 0)
            WHERE id = :hostId
            """)
    void updateOnFrontierUrlStateChange(long hostId, FrontierUrl.State oldState, FrontierUrl.State newState,
                                        Instant now, Instant nextVisit);

    @SqlUpdate("UPDATE hosts SET pending = pending + 1, next_visit = coalesce(next_visit, 0) WHERE id = ?")
    void incrementPendingAndInitNextVisit(long hostId);

    @SqlUpdate("UPDATE hosts SET last_visit = :now, next_visit = :nextVisit WHERE id = :id")
    void updateNextVisit(long id, Instant now, Instant nextVisit);

    @SqlQuery("SELECT COUNT(*) FROM hosts " + HOSTS_WHERE)
    long count(@BindFields Webapp.HostsQuery query);

    @SqlQuery("""
            SELECT * FROM hosts
            """ + HOSTS_WHERE + """
            <orderBy> LIMIT :limit OFFSET (:page - 1) * :limit""")
    @DefineNamedBindings
    List<Host> queryHosts(@Define String orderBy, @BindFields Webapp.HostsQuery query);

    String HOSTS_WHERE = """
            WHERE (:rhost IS NULL OR rhost GLOB :rhost)
            """;

    @SqlUpdate("""
            UPDATE hosts
            SET next_visit = NULL
            WHERE id = :hostId
            AND NOT EXISTS (SELECT 1 FROM frontier f
                WHERE f.host_id = :hostId
                  AND f.state = 'PENDING');
            """)
    void clearNextVisitIfNoPendingUrls(long hostId);

    @SqlQuery("SELECT * FROM hosts WHERE rhost = ?")
    Host findByRHost(String rhost);
}
