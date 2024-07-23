package org.netpreserve.warcbot;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.customizer.Timestamped;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.netpreserve.warcbot.util.MustUpdate;
import org.netpreserve.warcbot.util.Url;

import java.time.Instant;
import java.util.*;

@RegisterConstructorMapper(Candidate.class)
public interface FrontierDAO {
    @SqlQuery("SELECT * FROM frontier WHERE url = ?")
    Candidate getCandidate(Url url);

    @SqlBatch("""
            INSERT INTO frontier (queue, depth, url, via, time_added, state)
            VALUES (:queue, :depth, :url, :via, :timeAdded, :state)
            ON CONFLICT(url) DO NOTHING""")
    boolean[] addCandidates(@BindMethods Collection<Candidate> candidates);

    @SqlUpdate("UPDATE frontier SET state = :state WHERE url = :url")
    @MustUpdate
    void setCandidateState(Url url, Candidate.State state);

    @SqlQuery("""
                UPDATE queues
                SET worker_id = :workerId
                WHERE name = (
                    SELECT name
                    FROM queues q
                    WHERE worker_id IS NULL
                    AND EXISTS(SELECT 1 FROM queue_state_counts qsc WHERE qsc.queue = q.name AND qsc.state = 'PENDING' AND qsc.count > 0)
                    AND next_visit < :now
                    ORDER BY last_visited
                    LIMIT 1
                )
                RETURNING name""")
    @Timestamped
    String takeNextQueue(int workerId);

    @SqlQuery("""
                UPDATE frontier SET state = 'IN_PROGRESS'
                WHERE url = (
                    SELECT url FROM frontier
                    WHERE queue = ? AND state = 'PENDING'
                    ORDER BY depth
                    LIMIT 1)
                RETURNING *
                """)
    @Nullable
    Candidate takeNextUrlFromQueue(String queue);

    @SqlUpdate("UPDATE queues SET last_visited = :now, next_visit = :now, worker_id = NULL WHERE name = :queue")
    @MustUpdate
    void releaseQueue(String queue, Instant now, Instant nextVisit);

    @SqlBatch("INSERT INTO queues (name) VALUES (?) ON CONFLICT(name) DO NOTHING")
    void addQueues(@NotNull Collection<String> names);

    @SqlUpdate("UPDATE queues SET worker_id = NULL WHERE worker_id IS NOT NULL")
    void unlockAllQueues();

    @SqlUpdate("UPDATE frontier SET state = 'PENDING' WHERE state = 'IN_PROGRESS'")
    void resetAllInProgressCandidates();

    @SqlQuery("SELECT * FROM frontier WHERE queue = ?")
    List<Candidate> findCandidatesByQueue(String queue);

    @SqlUpdate("INSERT INTO errors (page_id, url, date, stacktrace) VALUES (?, ?, ?, ?)")
    void addError(UUID pageId, Url url, Instant date, String stacktrace);

    @SqlQuery("""
        WITH ranked_frontier AS (
            SELECT *, ROW_NUMBER() OVER (PARTITION BY queue <orderBy>) AS row_num
            FROM frontier)
        SELECT *
        FROM ranked_frontier
        WHERE row_num <= 5
        LIMIT :limit
        OFFSET :offset;
        """)
    List<Candidate> queryFrontier(@Define String orderBy, int limit, long offset);

    @SqlQuery("""
    SELECT * FROM queue_state_counts WHERE queue IN (<queues>)
    """)

    @RegisterConstructorMapper(QueueStateCount.class)
    List<QueueStateCount> getQueueStateCounts1(@BindList Set<String> queues);

    default Map<String, Map<Candidate.State, Long>> getQueueStateCounts(@BindList Set<String> queues) {
        var map = new HashMap<String, Map<Candidate.State, Long>>();
        for (var qsc: getQueueStateCounts1(queues)) {
            map.computeIfAbsent(qsc.queue, k -> new HashMap<>()).put(qsc.state, qsc.count);
        }
        return map;
    }


    record QueueStateCount(String queue, Candidate.State state, long count) {
    }


}
