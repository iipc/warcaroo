package org.netpreserve.warcbot.cdp;

import org.netpreserve.warcbot.cdp.domains.Network;
import org.netpreserve.warcbot.cdp.domains.Page.FrameId;
import org.netpreserve.warcbot.util.BareMediaType;
import org.netpreserve.warcbot.util.Url;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Objects;

public record ResourceFetched(
        String method,
        Url url,
        byte[] requestHeader,
        byte[] requestBody,
        byte[] responseHeader,
        byte[] responseBody,
        FileChannel responseBodyChannel,
        String ipAddress,
        long fetchTimeMs,
        int status,
        String redirect,
        BareMediaType responseType,
        Network.ResourceType type,
        String protocol,
        long transferred,
        FrameId frameId,
        Network.LoaderId loaderId,
        Network.RequestId requestId, java.time.Instant responseTime) implements Closeable {
    public ResourceFetched {
        Objects.requireNonNull(url);
    }

    public void close() {
        if (responseBodyChannel != null) {
            try {
                responseBodyChannel().close();
            } catch (IOException ignored) {
            }
        }
    }
}
