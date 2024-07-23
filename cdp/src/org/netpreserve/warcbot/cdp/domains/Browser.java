package org.netpreserve.warcbot.cdp.domains;

import java.util.function.Consumer;

public interface Browser {
    void close();

    Version getVersion();

    void setDownloadBehavior(String behavior, String downloadPath, boolean eventsEnabled);

    void onDownloadProgress(Consumer<DownloadProgress> event);

    record Version(String protocolVersion, String product, String revision, String userAgent,
                   String jsVersion) {
    }

    record DownloadProgress(String guid, long totalBytes, long receivedBytes, String state) {
    }
}
