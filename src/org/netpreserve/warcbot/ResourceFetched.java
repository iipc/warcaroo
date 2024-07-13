package org.netpreserve.warcbot;

record ResourceFetched(
        String url,
        byte[] requestHeader,
        byte[] requestBody,
        byte[] responseHeader,
        byte[] responseBody,
        String ipAddress,
        long fetchTimeMs,
        int status,
        String redirect,
        String responseType) {
}
