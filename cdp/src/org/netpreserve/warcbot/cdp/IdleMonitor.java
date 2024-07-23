package org.netpreserve.warcbot.cdp;

import java.util.concurrent.TimeUnit;

public class IdleMonitor {
    int inflight;

    public synchronized void started() {
        inflight++;
        if (inflight == 1) {
            notifyAll();
        }
    }

    public synchronized void finished() {
        inflight--;
        if (inflight < 0) throw new IllegalStateException();
        if (inflight == 0) {
            notifyAll();
        }
    }

    public synchronized void waitUntilIdle() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            while (inflight > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                waitNanos(remaining);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return;
            waitNanos(Math.min(remaining, 500_000_000));
        } while (inflight > 0);
    }

    private void waitNanos(long nanos) throws InterruptedException {
        wait(nanos / 1_000_000, (int)(nanos % 1_000_000));
    }
}
