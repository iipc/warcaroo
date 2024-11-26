package org.netpreserve.warcaroo.cdp.domains;

import org.netpreserve.warcaroo.util.Url;

import java.util.function.Consumer;

public interface Browser {
    void close();

    Version getVersion();

    void setDownloadBehavior(String behavior, String downloadPath, boolean eventsEnabled);

    void onDownloadProgress(Consumer<DownloadProgress> event);

    void onDownloadWillBegin(Consumer<DownloadWillBegin> event);

    void cancelDownload(String guid);

    record Version(String protocolVersion, String product, String revision, String userAgent,
                   String jsVersion) {
    }

    record DownloadProgress(String guid, long totalBytes, long receivedBytes, String state) {
    }

    record DownloadWillBegin(Page.FrameId frameId, String guid, Url url, String suggestedFilename) {
    }
}
