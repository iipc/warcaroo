package org.netpreserve.warcaroo;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Periodically snapshots the current crawl progress.
 */
public class ProgressTracker implements AutoCloseable {
    private final ProgressDAO dao;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> snapshotTask;
    private volatile Instant sessionStartTime;
    private static final long snapshotIntervalMillis = 60000;

    public ProgressTracker(ProgressDAO dao) {
        this.dao = dao;
        recoverLostRuntime();
    }

    /**
     * In the event of an unclear shutdown try to recover the total runtime fom the last snapshot. While we might lose
     * some time this at least ensures that the runtime of snapshots doesn't go backwards.
     * FIXME: maybe instead update runtime on every progress write?
     */
    private void recoverLostRuntime() {
        long lastRuntime = dao.lastRuntime();
        long currentRuntime = dao.totalRuntime();
        if (lastRuntime > currentRuntime) {
            dao.addSessionRuntime(lastRuntime - currentRuntime);
        }
    }

    public synchronized void startSession() {
        // shift the initial delay to align the snapshots relative to the total crawl runtime
        long initialDelay = snapshotIntervalMillis - (dao.totalRuntime() % snapshotIntervalMillis);
        sessionStartTime = Instant.now();
        this.snapshotTask = scheduler.scheduleAtFixedRate(this::snapshot, initialDelay, snapshotIntervalMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized void stopSession() {
        if (sessionStartTime == null) return;
        snapshotTask.cancel(false);
        snapshotTask = null;
        sessionStartTime = null;
        dao.addSessionRuntime(Duration.between(sessionStartTime, Instant.now()).toMillis());
    }

    private synchronized void snapshot() {
        if (sessionStartTime == null) return;
        Instant now = Instant.now();
        dao.createSnapshot(now, Duration.between(sessionStartTime, now).toMillis());
    }

    @Override
    public void close() {
        stopSession();
        scheduler.shutdownNow();
    }

    public Progress current() {
        Progress progress = dao.current();
        Instant sessionStartTime = this.sessionStartTime;
        if (sessionStartTime != null) {
            progress = progress.withRuntime(progress.runtime() + Duration.between(sessionStartTime, Instant.now()).toMillis());
        }
        return progress;
    }
}
