package org.netpreserve.warcbot;

import org.junit.jupiter.api.Test;
import org.netpreserve.warcbot.util.Url;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {

    @Test
    public void test() throws SQLException, IOException {
        Candidate candidate = new Candidate("example.com",
                new Url("http://example.com/foo"), 1, new Url("http://pants/"),
                Instant.now().truncatedTo(ChronoUnit.MILLIS), Candidate.State.CRAWLED);
        try (Database database = new Database("jdbc:sqlite::memory:")) {
            var frontierDAO = database.frontier();
            frontierDAO.addCandidates(Collections.singleton(candidate));
            var candidate2 = frontierDAO.getCandidate(candidate.url());
            assertEquals(candidate, candidate2);
        }
    }

}