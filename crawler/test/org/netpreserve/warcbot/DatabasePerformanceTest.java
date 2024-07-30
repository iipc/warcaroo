package org.netpreserve.warcbot;

import org.netpreserve.warcbot.util.Url;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

public class DatabasePerformanceTest {
    private static final int NUM_HOSTS = 1000;
    private static final int URLS_PER_HOST = 1000;
    private static final String[] TLD = {".com", ".org", ".net", ".edu", ".gov"};
    private static final Random random = new Random();

    private final Database db;
    private final List<String> hosts = new ArrayList<>();

    public DatabasePerformanceTest() throws SQLException, IOException {
        Path dbFile = Path.of("data", "performance_test.db");
        Files.deleteIfExists(dbFile);
        this.db = new Database(dbFile);
    }

    public void generateDummyData() {
        System.out.println("Generating dummy data...");
        long start = System.currentTimeMillis();

        // Generate hosts
        for (int i = 0; i < NUM_HOSTS; i++) {
            String host = "host" + i + TLD[random.nextInt(TLD.length)];
            hosts.add(host);
        }

        // Generate frontier data
        List<Candidate> candidates = new ArrayList<>();
        for (String host : hosts) {
            for (int i = 0; i < URLS_PER_HOST; i++) {
                Url url = new Url("http://" + host + "/page" + i);
                candidates.add(new Candidate(host, url, random.nextInt(5),
                        null, Instant.now(), Candidate.State.values()[random.nextInt(Candidate.State.values().length)]));
            }
        }
        db.frontier().addCandidates(candidates);

        long end = System.currentTimeMillis();
        System.out.println("Data generation completed in " + (end - start) + " ms");
    }

    public void runPerformanceTests() {
        System.out.println("Running performance tests...");

        // Test taking next URL from queue
        long start = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            String queue = db.frontier().takeNextQueue(1);
            if (queue != null) {
                db.frontier().takeNextUrlFromQueue(queue);
            }
        }
        long end = System.currentTimeMillis();
        System.out.println("1000 takeNextUrlFromQueue operations completed in " + (end - start) + " ms");

        // Test adding new URLs
        start = System.currentTimeMillis();
        List<Candidate> newCandidates = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            String host = hosts.get(random.nextInt(hosts.size()));
            Url url = new Url("http://" + host + "/newpage" + i);
            newCandidates.add(new Candidate(host, url, random.nextInt(5),
                    null, Instant.now(), Candidate.State.PENDING));
        }
        db.frontier().addCandidates(newCandidates);
        end = System.currentTimeMillis();
        System.out.println("Adding 10000 new URLs completed in " + (end - start) + " ms");

        // Test updating candidate state
        start = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            Candidate candidate = newCandidates.get(i);
            db.frontier().setCandidateState(candidate.url(), Candidate.State.CRAWLED);
        }
        end = System.currentTimeMillis();
        System.out.println("1000 candidate state updates completed in " + (end - start) + " ms");

        // Test robots.txt lookup
        start = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            String host = hosts.get(random.nextInt(hosts.size()));
            db.robotsTxt().getRobotsTxt("http://" + host + "/robots.txt");
        }
        end = System.currentTimeMillis();
        System.out.println("1000 robots.txt lookups completed in " + (end - start) + " ms");
    }

    public static void main(String[] args) throws SQLException, IOException {
        DatabasePerformanceTest test = new DatabasePerformanceTest();
        test.generateDummyData();
        test.runPerformanceTests();
    }
}