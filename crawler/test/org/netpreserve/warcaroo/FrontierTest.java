package org.netpreserve.warcaroo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.netpreserve.warcaroo.config.CrawlConfig;
import org.netpreserve.warcaroo.config.MatchRule;
import org.netpreserve.warcaroo.config.ScopeConfig;
import org.netpreserve.warcaroo.util.Url;
import org.netpreserve.warcaroo.webapp.Webapp;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(InMemoryDatabaseTestExtension.class)
class FrontierTest {

    private final Database database;
    private Frontier frontier;
    private CrawlConfig crawlConfig;

    FrontierTest(Database database) {
        this.database = database;
    }

    @BeforeEach
    void setUp() {
        this.crawlConfig = new CrawlConfig("test", null, null, null, 5, 1000);

        frontier = new Frontier(database, new Scope(new ScopeConfig(List.of(new MatchRule.Regex("^https?://(www\\.)?example\\.(com|org)")),
                List.of())), crawlConfig);

        // Clear relevant tables before each test
        database.useHandle(handle -> {
            handle.execute("DELETE FROM frontier");
            handle.execute("DELETE FROM hosts");
            handle.execute("DELETE FROM domains");
        });
    }

    @Test
    void testAddUrl() {
        Url url = new Url("http://example.com");
        Url via = new Url("http://referrer.com");

        frontier.addUrl(url, 0, via);

        FrontierUrl addedUrl = database.frontier().findByUrl(url);
        assertNotNull(addedUrl);
        assertEquals(url, addedUrl.url());
        assertEquals(0, addedUrl.depth());
        assertEquals(via, addedUrl.via());
        assertEquals(FrontierUrl.State.PENDING, addedUrl.state());

        Host host = database.hosts().findByRHost(Url.reverseHost("example.com"));
        assertNotNull(host);
        assertEquals(1, host.pending());

        var domain = database.domains().findByRHost(Url.reverseHost("example.com"));
        assertNotNull(domain);
        assertEquals(1, domain.pending());
    }

    @Test
    void testAddUrls() {
        Url url1 = new Url("http://example.com");
        Url url2 = new Url("http://example.org");
        Url url3 = new Url("http://example.net");  // This should be out of scope
        Url via = new Url("http://referrer.com");

        frontier.addUrls(Arrays.asList(url1, url2, url3), 0, via);

        assertEquals(2, database.frontier().count(new Webapp.FrontierQuery()));
        assertEquals(2, database.hosts().count(new Webapp.HostsQuery()));
        assertEquals(2, database.domains().count(new Webapp.HostsQuery()));
    }

    @Test
    void testTakeNext() throws CrawlLimitException {
        Url url = new Url("http://example.com");
        frontier.addUrl(url, 0, null);

        FrontierUrl takenUrl = frontier.takeNext();

        assertNotNull(takenUrl);
        assertEquals(url, takenUrl.url());
    }

    @Test
    void testTakeNextWithNoAvailableUrls() throws CrawlLimitException {
        FrontierUrl result = frontier.takeNext();
        assertNull(result);
    }

    @Test
    void testRelease() throws CrawlLimitException {
        Url url = new Url("http://example.com");
        frontier.addUrl(url, 0, null);
        FrontierUrl frontierUrl = frontier.takeNext();
        assertNotNull(frontierUrl);

        frontier.release(frontierUrl, FrontierUrl.State.CRAWLED);

        FrontierUrl updatedUrl = database.frontier().findByUrl(url);
        assertNotNull(updatedUrl);
        assertEquals(FrontierUrl.State.CRAWLED, updatedUrl.state());

        Host host = database.hosts().findByRHost(Url.reverseHost("example.com"));
        assertNotNull(host);
        assertEquals(0, host.pending());
        assertNotNull(host.lastVisit());
        assertNotNull(host.nextVisit());

        Domain domain = database.domains().findByRHost(Url.reverseHost("example.com"));
        assertNotNull(domain);
        assertEquals(0, domain.pending());
    }

    @Test
    void testScopeEnforcement() {
        Url inScopeUrl1 = new Url("http://example.com");
        Url inScopeUrl2 = new Url("https://www.example.org");
        Url outOfScopeUrl1 = new Url("http://example.net");
        Url outOfScopeUrl2 = new Url("http://test.com");

        frontier.addUrls(Arrays.asList(inScopeUrl1, inScopeUrl2, outOfScopeUrl1, outOfScopeUrl2), 0, null);

        assertEquals(2, database.frontier().count(new Webapp.FrontierQuery()));
        assertNotNull(database.frontier().findUrl(inScopeUrl1));
        assertNotNull(database.frontier().findUrl(inScopeUrl2));
        assertNull(database.frontier().findUrl(outOfScopeUrl1));
        assertNull(database.frontier().findUrl(outOfScopeUrl2));
    }

    @Test
    void testCrawlDelay() throws CrawlLimitException {
        Url url = new Url("http://example.com");
        frontier.addUrl(url, 0, null);
        FrontierUrl frontierUrl = frontier.takeNext();
        assertNotNull(frontierUrl);

        frontier.release(frontierUrl, FrontierUrl.State.CRAWLED);

        Host host = database.hosts().findByRHost(Url.reverseHost("example.com"));
        assertNotNull(host);
        assertNotNull(host.nextVisit());
        assertTrue(host.nextVisit().equals(host.lastVisit().plusMillis(crawlConfig.delay())));
    }
}