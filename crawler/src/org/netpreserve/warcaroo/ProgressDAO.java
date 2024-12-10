package org.netpreserve.warcaroo;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import java.time.Instant;

@RegisterConstructorMapper(Progress.class)
public interface ProgressDAO {
    @SqlUpdate("UPDATE progress SET resources = resources + 1, size = size + :size WHERE id = 0")
    void addResourceProgress(long size);

    @SqlUpdate("UPDATE progress SET discovered = discovered + 1, pending = pending + 1 WHERE id = 0")
    void incrementPendingAndDiscovered();

    @SqlUpdate("UPDATE progress SET pending = pending - 1, crawled = crawled + 1 WHERE id = 0")
    void decrementPendingAndIncrementCrawled();

    @SqlUpdate("UPDATE progress SET pending = pending - 1, failed = failed + 1 WHERE id = 0")
    void decrementPendingAndIncrementFailed();

    @SqlUpdate("UPDATE progress SET pending = pending - 1 WHERE id = 0")
    void decrementPending();

    @SqlUpdate("INSERT INTO progress (date, runtime, discovered, pending, crawled, failed, resources, size) " +
               "SELECT :date, runtime + :sessionRuntime, discovered, pending, crawled, failed, resources, size FROM progress WHERE id = 0")
    long createSnapshot(Instant date, long sessionRuntime);

    @SqlUpdate("UPDATE progress SET runtime = runtime + :sessionRuntime WHERE id = 0")
    void addSessionRuntime(long sessionRuntime);

    @SqlQuery("SELECT runtime FROM progress WHERE id = 0")
    long totalRuntime();

    @SqlQuery("SELECT runtime FROM progress ORDER BY id DESC LIMIT 1")
    long lastRuntime();

    @SqlQuery("SELECT * FROM progress WHERE id = 0")
    Progress current();
}
