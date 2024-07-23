package org.netpreserve.warcbot.cdp;

import java.util.concurrent.atomic.AtomicLong;

public class Tracker {
    private AtomicLong bytesDownloaded = new AtomicLong();

    public void updateDownloadedBytes(long bytes) {
        bytesDownloaded.addAndGet(bytes);
    }

    public long downloadedBytes() {
        return bytesDownloaded.get();
    }
}
