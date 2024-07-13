package org.netpreserve.warcbot.cdp;

public interface Browser {
    void close();

    Version getVersion();

    record Version(String protocolVersion, String product, String revision, String userAgent,
                   String jsVersion) {
    }
}
