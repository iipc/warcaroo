package org.netpreserve.warcbot;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.netpreserve.warcbot.util.Url;
import org.netpreserve.warcbot.webapp.Webapp;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(InMemoryDatabaseTestExtension.class)
class HostDAOTest {

    private final Database database;
    private final HostDAO hostDAO;

    HostDAOTest(Database database) {
        this.database = database;
        hostDAO = database.hosts();
    }

    @SuppressWarnings("SqlWithoutWhere")
    @BeforeEach
    public void setUp() {
        database.withHandle(h -> h.execute("DELETE FROM hosts"));
        database.withHandle(h -> h.execute("DELETE FROM frontier"));
    }

    @Test
    void testInsertOrGetId() {
        long id1 = hostDAO.insertOrGetId("example.com");
        long id2 = hostDAO.insertOrGetId("example.com");
        assertEquals(id1, id2, "Inserting the same host twice should return the same ID");

        long id3 = hostDAO.insertOrGetId("example.org");
        assertNotEquals(id1, id3, "Inserting a different host should return a different ID");
    }

    @Test
    void testFindNextToVisit() {
        Instant now = Instant.now();
        Instant future = now.plusSeconds(3600);

        long host1Id = hostDAO.insertOrGetId("host1.com");
        long host2Id = hostDAO.insertOrGetId("host2.com");
        long host3Id = hostDAO.insertOrGetId("host3.com");

        hostDAO.updateNextVisit(host1Id, now, now.minusSeconds(3600));
        hostDAO.updateNextVisit(host2Id, now, future);
        hostDAO.updateNextVisit(host3Id, now, now.minusSeconds(1800));

        Long nextToVisit = hostDAO.findNextToVisit(now, List.of(host2Id));
        assertNotNull(nextToVisit);
        assertEquals(host1Id, nextToVisit);
    }

    @Test
    void testUpdateOnFrontierUrlStateChange() {
        long hostId = hostDAO.insertOrGetId("example.com");
        Instant now = Instant.now();
        Instant nextVisit = now.plusSeconds(3600);

        hostDAO.updateOnFrontierUrlStateChange(hostId, FrontierUrl.State.PENDING, FrontierUrl.State.FAILED, now, nextVisit);

        Host host = hostDAO.find(hostId);
        assertEquals(now.truncatedTo(ChronoUnit.MILLIS), host.lastVisit());
        assertEquals(nextVisit.truncatedTo(ChronoUnit.MILLIS), host.nextVisit());
        assertEquals(-1, host.pending());
        assertEquals(1, host.failed());
        assertEquals(0, host.robotsExcluded());
    }

    @Test
    void testIncrementPendingAndInitNextVisit() {
        long hostId = hostDAO.insertOrGetId("example.com");
        hostDAO.incrementPendingAndInitNextVisit(hostId);

        Host host = hostDAO.find(hostId);
        assertEquals(1, host.pending());
        assertNotNull(host.nextVisit());
    }

    @Test
    void testUpdateNextVisit() {
        long hostId = hostDAO.insertOrGetId("example.com");
        Instant now = Instant.now();
        Instant nextVisit = now.plusSeconds(3600);

        hostDAO.updateNextVisit(hostId, now, nextVisit);

        Host host = hostDAO.find(hostId);

        assertEquals(now.truncatedTo(ChronoUnit.MILLIS), host.lastVisit());
        assertEquals(nextVisit.truncatedTo(ChronoUnit.MILLIS), host.nextVisit());
    }

    @Test
    void testCountAndQueryHosts() {
        hostDAO.insertOrGetId(Url.reverseHost("example.com"));
        hostDAO.insertOrGetId(Url.reverseHost("example.org"));
        hostDAO.insertOrGetId(Url.reverseHost("subdomain.example.com"));

        Webapp.HostsQuery query = new Webapp.HostsQuery();
        query.setHost("*.example.com");

        long count = hostDAO.count(query);
        assertEquals(2, count);

        List<Host> hosts = hostDAO.queryHosts("ORDER BY rhost DESC", query);
        assertEquals(2, hosts.size());
        assertEquals("com,example,subdomain,", hosts.get(0).rhost());
        assertEquals("com,example,", hosts.get(1).rhost());
    }

    @Test
    void testClearNextVisitIfNoPendingUrls() {
        long hostId = hostDAO.insertOrGetId("example.com");
        Instant nextVisit = Instant.now().plusSeconds(3600);

        hostDAO.updateNextVisit(hostId, Instant.now(), nextVisit);

        hostDAO.clearNextVisitIfNoPendingUrls(hostId);

        {
            Host host = hostDAO.find(hostId);
            assertNull(host.nextVisit());
        }

        // Now let's add a pending URL and test again
        long domainId = database.domains().insertOrGetId("com,example,");
        database.frontier().addUrl0(new Url("http://example.com/"), hostId, domainId, 0, null, Instant.now(),
                FrontierUrl.State.PENDING);
        database.hosts().incrementPendingAndInitNextVisit(hostId);

        hostDAO.clearNextVisitIfNoPendingUrls(hostId);

        {
            Host host = hostDAO.find(hostId);
            assertNotNull(host.nextVisit());
        }
    }
}