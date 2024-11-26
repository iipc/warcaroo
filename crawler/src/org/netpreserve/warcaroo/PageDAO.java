package org.netpreserve.warcaroo;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.netpreserve.warcaroo.util.MustUpdate;
import org.netpreserve.warcaroo.util.Url;
import org.netpreserve.warcaroo.webapp.Webapp;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

@RegisterConstructorMapper(Page.class)
@RegisterConstructorMapper(Page.Ext.class)
public interface PageDAO {
    @SqlUpdate("INSERT INTO pages (url, host_id, domain_id, date) VALUES (:url, :hostId, :domainId, :date)")
    @GetGeneratedKeys
    long _create(Url url, long hostId, long domainId, Instant date);

    @Transaction
    default long create(Url url, long hostId, long domainId, Instant date) {
        long id = _create(url, hostId, domainId, date);
        _addPageToHost(hostId);
        _addPageToDomain(domainId);
        return id;
    }

    @SqlUpdate("""
               UPDATE pages
               SET title = :title,
                   visit_time_ms = :visitTimeMs,
                   main_resource_id = :mainResourceId
               WHERE id = :pageId""")
    @MustUpdate
    void finish(long pageId, String title, long visitTimeMs, Long mainResourceId);

    @SqlUpdate("INSERT INTO pages (id, url, date, title, visit_time_ms, host_id, domain_id) " +
               "VALUES (:id, :url, :date, :title, :visitTimeMs, :hostId, :domainId)")
    void _addPage(@BindMethods Page page);

    @SqlUpdate("UPDATE hosts SET pages = pages + 1 WHERE id = :hostId")
    @MustUpdate
    void _addPageToHost(long hostId);

    @SqlUpdate("UPDATE domains SET pages = pages + 1 WHERE id = :domainId")
    @MustUpdate
    void _addPageToDomain(long domainId);

    String PAGES_WHERE = " WHERE (:hostId IS NULL OR pages.host_id = :hostId) ";

    @SqlQuery("SELECT COUNT(*) FROM pages " + PAGES_WHERE)
    long count(@BindFields Webapp.PagesQuery query);

    @SqlQuery("""
            SELECT pages.*, r.status
            FROM pages
            LEFT JOIN resources r ON r.id = pages.main_resource_id
            """ + PAGES_WHERE + """
            <orderBy> LIMIT :limit OFFSET :offset""")
    List<Page.Ext> query(@BindFields Webapp.PagesQuery query, @Define String orderBy, int limit, long offset);

    default void error(long pageId, Throwable e) {
        var buffer = new StringWriter();
        e.printStackTrace(new PrintWriter(buffer));
        error(pageId, buffer.toString());
    }

    @SqlUpdate("UPDATE pages SET error = :error WHERE id = :pageId")
    @MustUpdate
    void error(long pageId, String error);

    @SqlUpdate("UPDATE pages SET resources = resources + 1, size = size + :size WHERE id = :pageId")
    @MustUpdate
    void addResourceToPage(long pageId, long size);
}
