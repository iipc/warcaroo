package org.netpreserve.warcbot.cdp;

import org.netpreserve.warcbot.cdp.domains.Network;
import org.netpreserve.warcbot.util.BareMediaType;
import org.netpreserve.warcbot.util.Url;

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
        String protocol) {
    public ResourceFetched {
        Objects.requireNonNull(url);
    }
}
