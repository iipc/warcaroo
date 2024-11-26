package org.netpreserve.warcaroo;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.netpreserve.warcaroo.util.Url;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(InMemoryDatabaseTestExtension.class)
class PageDAOTest {

    private final Database database;

    PageDAOTest(Database database) {
        this.database = database;
    }

    @Test
    public void testCreate() {
        Url url = Url.orNull("http://www.example42.com/");

        long hostId = database.hosts().insertOrGetId(url.rhost());
        long domainId = database.domains().insertOrGetId(url.rdomain());
        database.pages().create(url, hostId, domainId, Instant.now());

        Host host = database.hosts().find(hostId);
        Domain domain = database.domains().find(domainId);

        assertEquals(1, host.pages());
        assertEquals(1, domain.pages());
    }
}
