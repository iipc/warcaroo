package org.netpreserve.warcaroo;

import org.netpreserve.warcaroo.config.JobConfig;
import org.netpreserve.warcaroo.util.Url;
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

public class Job implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Job.class);
    public final Database db;
    private final Frontier frontier;
    private final Storage storage;
    private final HttpClient httpClient;
    private final RobotsTxtChecker robotsTxtChecker;
    private final List<Worker> workers = new ArrayList<>();
    private final List<BrowserManager> browserManagers = new ArrayList<>();
    private final JobConfig config;
    private volatile State state = State.STOPPED;
    private final Lock startStopLock = new ReentrantLock();
    private final ProgressTracker progressTracker;

    public List<BrowserManager> browserManagers() {
        startStopLock.lock();
        try {
            return new ArrayList<>(browserManagers);
        } finally {
            startStopLock.unlock();
        }
    }

    public Progress progress() {
        return progressTracker.current();
    }

    public Frontier frontier() {
        return frontier;
    }

    public enum State {
        STOPPED, STARTING, RUNNING, STOPPING
    }

    public Job(Path dataPath, JobConfig config) throws IOException {
        this.config = config;
        this.db = Database.open(dataPath.resolve("db.sqlite3"));
        this.httpClient = HttpClient.newHttpClient();
        this.frontier = new Frontier(db, new Scope(config.seeds(), config.scope()), config.crawl());
        this.storage = new Storage(dataPath, db, config.storage());
        this.robotsTxtChecker = new RobotsTxtChecker(db.robotsTxt(), httpClient, storage,
                List.of("nla.gov.au_bot", "warcaroo"), config.crawl().userAgent());
        progressTracker = new ProgressTracker(db.progress());
    }

    public void close() {
        startStopLock.lock();
        try {
            state = State.STOPPING;
            closeAllBrowsers();
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
            progressTracker.close();
            try {
                db.close();
            } catch (Exception e) {
                log.error("Failed to close database", e);
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
            progressTracker.startSession();
            frontier.addUrls(config.seeds().stream().map(seed -> seed.url()).toList(), 0, null);
            for (var browserConfig : config.browsersOrDefault()) {
                BrowserManager browserManager = new BrowserManager(browserConfig);
                browserManagers.add(browserManager);
                for (int i = 0; i < browserConfig.workers(); i++) {
                    workers.add(new Worker(browserConfig.id() + "-" + i, browserManager, frontier, storage, db, robotsTxtChecker, this));
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
            progressTracker.stopSession();
            state = State.STOPPED;
        } finally {
            startStopLock.unlock();
        }
    }

    public BrowserManager browserManager() {
        if (browserManagers.isEmpty()) throw new RuntimeException("No browser processes are running");
        return browserManagers.getFirst();
    }

    public JobConfig config() {
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
