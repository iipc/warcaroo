package org.netpreserve.warcbot.cdp;

import java.io.IOException;

public interface CDPConnection {
    void send(CDPClient.Call message) throws IOException;

    void close();
}
