package org.netpreserve.warcbot;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.*;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transactional;
import org.netpreserve.warcbot.util.MustUpdate;
import org.netpreserve.warcbot.util.Url;
import org.netpreserve.warcbot.webapp.Webapp;

import java.time.Instant;
import java.util.*;

@RegisterConstructorMapper(FrontierUrl.class)
public interface FrontierDAO extends Transactional<FrontierDAO> {
    @SqlQuery("SELECT * FROM frontier WHERE url = ?")
    FrontierUrl getCandidate(Url url);

    @SqlUpdate("UPDATE frontier SET state = :state WHERE id = :id")
    @MustUpdate
    void updateState(long id, FrontierUrl.State state);

    @SqlQuery("SELECT * FROM frontier WHERE host_id = :hostId AND state = 'PENDING'")
    FrontierUrl nextUrlForHost(long hostId);

    @SqlUpdate("INSERT INTO errors (page_id, url, date, stacktrace) VALUES (?, ?, ?, ?)")
    void addError(UUID pageId, Url url, Instant date, String stacktrace);

    String FRONTIER_WHERE = """
            WHERE (:depth IS NULL OR depth = :depth)
              AND (:state IS NULL OR state = :state)
            """;

    @SqlQuery("SELECT COUNT(*) FROM frontier\n" + FRONTIER_WHERE)
    long countFrontier(@BindFields Webapp.FrontierQuery query);

    @SqlQuery("""
        SELECT * FROM frontier
        """ + FRONTIER_WHERE + """ 
        <orderBy> LIMIT :limit OFFSET (:page - 1) * :limit
        """)
    List<FrontierUrl> queryFrontier(@Define String orderBy, @BindFields Webapp.FrontierQuery query);


    @SqlQuery("SELECT id FROM frontier WHERE url = ?")
    Long findUrl(Url url);

    @SqlUpdate("""
            INSERT INTO frontier (depth, url, host_id, domain_id, via, time_added, state)
            VALUES (:depth, :url, :hostId, :domainId, :via, :timeAdded, :state)
            ON CONFLICT(url) DO NOTHING""")
    @GetGeneratedKeys
    Long addUrl0(Url url, long hostId, long domainId, int depth, Url via, Instant timeAdded, FrontierUrl.State state);
}
