package org.netpreserve.warcbot.cdp;

import org.netpreserve.warcbot.cdp.domains.Network;

import java.nio.channels.FileChannel;

public record ResourceFetched(
        String url,
        byte[] requestHeader,
        byte[] requestBody,
        byte[] responseHeader,
        byte[] responseBody,
        FileChannel responseBodyChannel,
        String ipAddress,
        long fetchTimeMs,
        int status,
        String redirect,
        String responseType,
        Network.ResourceType type,
        String protocol) {
}
