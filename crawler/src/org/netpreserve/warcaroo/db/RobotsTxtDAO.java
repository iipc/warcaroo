package org.netpreserve.warcaroo.db;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.netpreserve.warcaroo.RobotsTxt;

import java.time.Instant;

@RegisterConstructorMapper(RobotsTxt.class)
public interface RobotsTxtDAO {
    @SqlQuery("SELECT * FROM robotstxt WHERE url = ?")
    RobotsTxt getRobotsTxt(String url);

    @SqlUpdate("INSERT INTO robotstxt (url, date, last_checked, body) " +
               "VALUES (:url, :date, :date, :body) " +
               "ON CONFLICT (url) DO UPDATE SET date = excluded.date, " +
               " last_checked = excluded.last_checked, body = excluded.body")
    void saveRobotsTxt(String url, Instant date, byte[] body);

    @SqlUpdate("UPDATE robotstxt SET last_checked = :lastChecked WHERE url = :url")
    void updateRobotsTxtLastChecked(String url, Instant lastChecked);
}
