package org.netpreserve.warcbot.cdp;

public record ResourceFetched(
        String url,
        byte[] requestHeader,
        byte[] requestBody,
        byte[] responseHeader,
        byte[] responseBody,
        String ipAddress,
        long fetchTimeMs,
        int status,
        String redirect,
        String responseType,
        String type,
        String protocol) {
}