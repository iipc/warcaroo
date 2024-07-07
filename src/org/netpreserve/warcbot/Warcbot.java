package org.netpreserve.warcbot;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.slf4j.Logger;

public class Warcbot implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Warcbot.class);
    private final Database db;
    private final Frontier frontier;
    private final Storage storage;
    private final HttpClient httpClient;
    private final RobotsTxtChecker robotsTxtChecker;
    private final List<Worker> workers = new ArrayList<>();

    public Warcbot(Path dataPath, Predicate<String> scope) throws SQLException, IOException {
        this.db = new Database(dataPath.resolve("db.sqlite3"));
        this.httpClient = HttpClient.newHttpClient();
        this.frontier = new Frontier(db.frontier(), scope);
        this.storage = new Storage(dataPath, db.storage());
        this.robotsTxtChecker = new RobotsTxtChecker(db.robotsTxt(), httpClient, storage, List.of("nla.gov.au_bot", "warcbot"));
        workers.add(new Worker(0, new Browser(), frontier, storage, db, robotsTxtChecker));
        workers.add(new Worker(1, new Browser(), frontier, storage, db, robotsTxtChecker));
    }

    public void close() {
        for (Worker worker : workers) {
            try {
                worker.browser.close();
            } catch (Exception e) {
                log.error("Failed to close browser for worker {}", worker.id, e);
            }
        }
        try {
            db.close();
        } catch (Exception e) {
            log.error("Failed to close database", e);
        }
        try {
            storage.close();
        } catch (Exception e) {
            log.error("Failed to close storage", e);
        }
        try {
            httpClient.close();
        } catch (Exception e) {
            log.error("Failed to close http client", e);
        }
    }

    public void start() {
        for (Worker worker : workers) {
            worker.start();
        }
    }

    public static void main(String[] args) throws Exception {
        var scope = Pattern.compile("https?://([^/]+\\.)nla.gov.au(|/.*)").asPredicate();
        Warcbot warcbot = new Warcbot(Path.of("data"), scope);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                warcbot.close();
            } catch (Exception e) {
                log.error("Error closing Warcbot", e);
            }
        }, "shutdown-hook"));
        warcbot.frontier.addUrl(new Url("https://www.nla.gov.au/"), 0, null);
        warcbot.start();
    }
}
