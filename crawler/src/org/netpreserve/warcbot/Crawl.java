package org.netpreserve.warcaroo;

import org.netpreserve.warcaroo.webapp.OpenAPI.Doc;
import org.netpreserve.warcaroo.webapp.Route.HttpError;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
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
    private final List<BrowserManager> browserManagers = new ArrayList<>();
    private final Config config;
    private volatile State state = State.STOPPED;
    private final Lock startStopLock = new ReentrantLock();

    public List<BrowserManager> browserManagers() {
        startStopLock.lock();
        try {
            return new ArrayList<>(browserManagers);
        } finally {
            startStopLock.unlock();
        }
    }

    public enum State {
        STOPPED, STARTING, RUNNING, STOPPING
    }

    public Crawl(Path dataPath, Config config) throws IOException {
        this.config = config;
        this.db = Database.open(dataPath.resolve("db.sqlite3"));
        this.httpClient = HttpClient.newHttpClient();
        this.frontier = new Frontier(db, config.getScope(), config);
        this.storage = new Storage(dataPath, db, config);
        this.robotsTxtChecker = new RobotsTxtChecker(db.robotsTxt(), httpClient, storage,
                List.of("nla.gov.au_bot", "warcaroo"), config);
    }

    public void close() {
        startStopLock.lock();
        try {
            state = State.STOPPING;
            closeAllBrowsers();
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

    public void start() throws BadStateException, IOException {
        if (!startStopLock.tryLock()) throw new BadStateException("Crawl busy " + state);
        try {
            if (state != State.STOPPED) throw new BadStateException("Can only start a STOPPED crawl");
            state = State.STARTING;
            frontier.addUrls(config.getSeeds(), 0, null);
            for (var browserSettings : config.getBrowsers()) {
                BrowserManager browserManager = new BrowserManager(browserSettings);
                browserManagers.add(browserManager);
                for (int i = 0; i < browserSettings.workers(); i++) {
                    workers.add(new Worker(browserSettings.id() + "-" + i, browserManager, frontier, storage, db, robotsTxtChecker, config));
                }
            }
            for (Worker worker : workers) {
                worker.start();
            }
            state = State.RUNNING;
        } catch (Throwable e) {
            closeAllBrowsers();
            throw e;
        } finally {
            startStopLock.unlock();
        }
    }

    private void closeAllBrowsers() {
        for (var worker : workers) {
            try {
                worker.close();
            } catch (Exception ignored) {
            }
        }
        workers.clear();
        for (BrowserManager browserProcess : browserManagers) {
            try {
                browserProcess.close();
            } catch (Exception ignored) {
            }
        }
        browserManagers.clear();
    }

    public void stop() throws BadStateException {
        if (!startStopLock.tryLock()) throw new BadStateException("Crawl busy " + state);
        try {
            if (state != State.RUNNING) throw new BadStateException("Can only stop a RUNNING crawl");
            state = State.STOPPING;
            for (Worker worker : workers) {
                worker.closeAsyncGraceful();
            }
            closeAllBrowsers();
            state = State.STOPPED;
        } finally {
            startStopLock.unlock();
        }
    }

    public BrowserManager browserManager() {
        if (browserManagers.isEmpty()) throw new RuntimeException("No browser processes are running");
        return browserManagers.getFirst();
    }

    public Config config() {
        return config;
    }

    public State state() {
        return state;
    }

    public List<Worker.Info> workerInfo() {
        List<Worker> workers;
        startStopLock.lock();
        try {
            workers = new ArrayList<>(this.workers);
        } finally {
            startStopLock.unlock();
        }
        List<Worker.Info> infoList = new ArrayList<>(workers.size());
        for (var worker : workers) {
            infoList.add(worker.info());
        }
        return infoList;
    }

    @HttpError(409)
    @Doc("The crawl was not in an appropriate state for this action.")
    public static class BadStateException extends Exception {
        public BadStateException(String message) {
            super(message);
        }
    }
}
