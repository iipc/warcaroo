package org.netpreserve.warcbot;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.netpreserve.warcbot.cdp.NavigationException;
import org.netpreserve.warcbot.util.MustUpdate;
import org.netpreserve.warcbot.util.Url;
import org.netpreserve.warcbot.webapp.Webapp;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

public interface PageDAO {
    @Transaction
    default void add(@BindMethods Page page) {
        _addPage(page);
        _addPageToHost(page);
        _addPageToDomain(page);
    }

    @SqlUpdate("INSERT INTO pages (url, host_id, domain_id, date) VALUES (:url, :hostId, :domainId, :date)")
    @GetGeneratedKeys
    long create(Url url, long hostId, long domainId, Instant date);

    @SqlUpdate("UPDATE pages SET title = :title, visit_time_ms = :visitTimeMs WHERE id = :pageId")
    @MustUpdate
    void finish(long pageId, String title, long visitTimeMs);

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
    long count(@BindFields Webapp.PagesQuery query);

    @RegisterConstructorMapper(Page.class)
    @SqlQuery("""
            SELECT *
            FROM pages
            """ + PAGES_WHERE + """
            <orderBy> LIMIT :limit OFFSET :offset""")
    List<Page> query(@BindFields Webapp.PagesQuery query, @Define String orderBy, int limit, long offset);

    default void error(Long pageId, Throwable e) {
        var buffer = new StringWriter();
        e.printStackTrace(new PrintWriter(buffer));
        error(pageId, buffer.toString());
    }

    @SqlUpdate("UPDATE pages SET error = :error WHERE id = :id")
    @MustUpdate
    void error(Long pageId, String error);

    @SqlUpdate("UPDATE pages SET resources = resources + 1, size = size + :size WHERE id = :pageId")
    @MustUpdate
    void addResourceToPage(long pageId, long size);
}
