package org.netpreserve.warcbot;

import org.netpreserve.warcbot.cdp.BrowserProcess;
import org.netpreserve.warcbot.cdp.Tracker;
import org.netpreserve.warcbot.util.Url;
import org.netpreserve.warcbot.webapp.OpenAPI.Doc;
import org.netpreserve.warcbot.webapp.Route.HttpError;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;

public class Crawl implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Crawl.class);
    public final Database db;
    private final Frontier frontier;
    private final Storage storage;
    private final HttpClient httpClient;
    private final RobotsTxtChecker robotsTxtChecker;
    private final List<Worker> workers = new ArrayList<>();
    private final BrowserProcess browserProcess;
    public final Tracker tracker;
    private final Config config;
    private volatile State state = State.STOPPED;
    private final Lock startStopLock = new ReentrantLock();

    enum State {
        STOPPED, STARTING, RUNNING, STOPPING
    }

    public Crawl(Path dataPath, Config config) throws SQLException, IOException {
        this.config = config;
        this.db = new Database(dataPath.resolve("db.sqlite3"));
        this.httpClient = HttpClient.newHttpClient();
        this.frontier = new Frontier(db.frontier(), config.getScope(), config);
        this.storage = new Storage(dataPath, db.storage());
        this.robotsTxtChecker = new RobotsTxtChecker(db.robotsTxt(), httpClient, storage,
                List.of("nla.gov.au_bot", "warcbot"), config.getUserAgent());
        this.browserProcess = BrowserProcess.start(config.getBrowserBinary(), dataPath.resolve("profile"),
                config.isHeadless());
        this.tracker = new Tracker();
    }

    public void close() {
        startStopLock.lock();
        try {
            state = State.STOPPING;
            for (Worker worker : workers) {
                try {
                    worker.close();
                } catch (Exception e) {
                    log.error("Failed to close worker {}", worker.id, e);
                }
            }
            try {
                browserProcess.close();
            } catch (Exception e) {
                log.error("Failed to close browser process", e);
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
            state = State.STOPPED;
        } finally {
            startStopLock.unlock();
        }
    }

    public void start() throws BadStateException {
        if (!startStopLock.tryLock()) throw new BadStateException("Crawl busy " + state);
        try {
            if (state != State.STOPPED) throw new BadStateException("Can only start a STOPPED crawl");
            state = State.STARTING;
            frontier.addUrls(config.getSeeds(), 0, null);
            for (int i = 0; i < config.getWorkers(); i++) {
                workers.add(new Worker(i, browserProcess, frontier, storage, db, robotsTxtChecker, tracker, config));
            }
            for (Worker worker : workers) {
                worker.start();
            }
            state = State.RUNNING;
        } finally {
            startStopLock.unlock();
        }
    }

    public void stop() throws BadStateException {
        if (!startStopLock.tryLock()) throw new BadStateException("Crawl busy " + state);
        try {
            if (state != State.RUNNING) throw new BadStateException("Can only stop a RUNNING crawl");
            state = State.STOPPING;
            for (Worker worker : workers) {
                worker.closeAsyncGraceful();
            }
            for (Worker worker : workers) {
                worker.close();
            }
            workers.clear();
            state = State.STOPPED;
        } finally {
            startStopLock.unlock();
        }
    }

    public State state() {
        return state;
    }

    @HttpError(409)
    @Doc("The crawl was not in an appropriate state for this action.")
    public static class BadStateException extends Exception {
        public BadStateException(String message) {
            super(message);
        }
    }
}
