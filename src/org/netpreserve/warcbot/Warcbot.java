package org.netpreserve.warcbot;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;

public class Warcbot implements AutoCloseable, Crawl {
    private static final Logger log = LoggerFactory.getLogger(Warcbot.class);
    private final Database db;
    private final Frontier frontier;
    private final Storage storage;
    private final HttpClient httpClient;
    private final RobotsTxtChecker robotsTxtChecker;
    private final List<Worker> workers = new ArrayList<>();
    private Config config;

    public Warcbot(Path dataPath, Config config) throws SQLException, IOException {
        this.config = config;
        this.db = new Database(dataPath.resolve("db.sqlite3"));
        this.httpClient = HttpClient.newHttpClient();
        this.frontier = new Frontier(db.frontier(), config.getScope(), config);
        this.storage = new Storage(dataPath, db.storage());
        this.robotsTxtChecker = new RobotsTxtChecker(db.robotsTxt(), httpClient, storage, List.of("nla.gov.au_bot", "warcbot"));
    }

    public void close() {
        for (Worker worker : workers) {
            try {
                worker.close();
            } catch (Exception e) {
                log.error("Failed to close worker {}", worker.id, e);
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
        for (var seed : config.getSeeds()) {
            frontier.addUrl(new Url(seed), 0, null);
        }
        for (int i = 0; i < config.getWorkers(); i++) {
            workers.add(new Worker(i, new Browser(config), frontier, storage, db, robotsTxtChecker));
        }
        for (Worker worker : workers) {
            worker.start();
        }
    }

    public static void main(String[] args) throws Exception {
        Config config = new Config();

        for (int i = 0; i < args.length; i++) {
            try {
                switch (args[i]) {
                    case "-h", "--help" -> {
                        System.out.printf("""
                                Usage: warcbot [options] seed-url...
                                
                                Options:
                                  --browser PATH            Set the path to the browser binary
                                  --crawl-delay MILLIS      Wait this long before crawling another page from the same queue.
                                  --include REGEX           Include pages that match the specified REGEX pattern in the crawl scope.
                                  -A, --user-agent STR      Set the User-Agent string to identify the crawler to the server.
                                  -w, --workers INT         Specify the number of browser and worker threads to use (default is %d).
                                
                                Examples:
                                  warcbot --include "https?://([^/]+\\.)example\\.com/.*" -A "MyCrawler/1.0" -w 5
                                """, config.getWorkers());
                        return;
                    }
                    case "--browser" -> config.setBrowserBinary(args[++i]);
                    case "--crawl-delay" -> config.setCrawlDelay(Integer.parseInt(args[++i]));
                    case "--include" -> config.addInclude(args[++i]);
                    case "--seed-file", "--seedFile" -> config.loadSeedFile(Path.of(args[++i]));
                    case "-A", "--user-agent", "--userAgent" -> config.setUserAgent(args[++i]);
                    case "-w", "--workers" -> config.setWorkers(Integer.parseInt(args[++i]));
                    default -> {
                        if (args[i].startsWith("-")) {
                            System.err.println("warcbot: unknown option " + args[i]);
                            System.exit(1);
                        }
                        config.addSeed(args[i]);
                    }
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                System.err.println("warcbot: Missing value for option " + args[i - 1]);
                System.exit(1);
            } catch (NumberFormatException e) {
                System.err.println("warcbot: Invalid number format for option " + args[i - 1]);
                System.exit(1);
            } catch (IllegalArgumentException e) {
                System.err.println("warcbot: " + e.getMessage() + " for option " + args[i - 1]);
                System.exit(1);
            }
        }

        Warcbot warcbot = new Warcbot(Path.of("data"), config);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                warcbot.close();
            } catch (Exception e) {
                log.error("Error closing Warcbot", e);
            }
        }, "shutdown-hook"));
        warcbot.start();
    }

    @Override
    public Config getConfig() {
        return config;
    }

    @Override
    public void setConfig(Config config) {
        this.config = config;
    }

    @Override
    public void pause() {

    }

    @Override
    public void unpause() {

    }

    @Override
    public List<Candidate> listQueue(String queue) {
        return db.frontier().findCandidatesByQueue(queue);
    }
}
