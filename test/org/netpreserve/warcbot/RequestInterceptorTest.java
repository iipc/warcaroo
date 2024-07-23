package org.netpreserve.warcbot;

import org.junit.jupiter.api.Test;
import org.netpreserve.warcbot.cdp.Fetch;
import org.netpreserve.warcbot.cdp.Network;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.netpreserve.warcbot.RequestInterceptor.formatResponseHeader;

class RequestInterceptorTest {
    @Test
    void testFormatRequestHeaderWithExtraInfoHeaders() {
        var request = new Network.Request(
                "http://example.com/test",
                null,
                "GET",
                Map.of(
                        "Accept", "text/html",
                        "User-Agent", "MyCrawler/1.0"
                ),
                null
        );

        Map<String, String> extraInfoHeaders = Map.of(
                ":authority", "example.com",
                "Accept-Language", "en-US"
        );

        byte[] expected = (
                "GET /test HTTP/1.1\r\n" +
                "host: example.com\r\n" +
                "Accept-Language: en-US\r\n\r\n"
        ).getBytes(US_ASCII);

        byte[] actual = RequestInterceptor.formatRequestHeader(request, extraInfoHeaders);
        assertArrayEquals(expected, actual);
    }

    @Test
    void testFormatRequestHeaderWithoutExtraInfoHeaders() {
        var request = new Network.Request(
                "http://example.com/test",
                null,
                "GET",
                Map.of(
                        "Accept", "text/html",
                        "User-Agent", "MyCrawler/1.0"
                ),
                null
        );

        byte[] expected = (
                "GET /test HTTP/1.1\r\n" +
                "Accept: text/html\r\n" +
                "User-Agent: MyCrawler/1.0\r\n" +
                "Host: example.com\r\n\r\n"
        ).getBytes(US_ASCII);

        byte[] actual = RequestInterceptor.formatRequestHeader(request, null);
        assertArrayEquals(expected, actual);
    }

    @Test
    void testFormatResponseHeaderWithRawResponseHeader() {
        String rawResponseHeader = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Encoding: gzip\r\n\r\n";

        var expected = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n";

        Fetch.RequestPaused event = mock(Fetch.RequestPaused.class);
        byte[] actual = formatResponseHeader(event, rawResponseHeader);
        assertEquals(expected, new String(actual, US_ASCII));
    }

    @Test
    void testFormatResponseHeaderWithoutRawResponseHeader() {
        List<Fetch.HeaderEntry> responseHeaders = List.of(
                new Fetch.HeaderEntry("Content-Type", "text/html"),
                new Fetch.HeaderEntry("Content-Encoding", "gzip"),
                new Fetch.HeaderEntry("Cache-Control", "no-cache")
        );

        Fetch.RequestPaused event = new Fetch.RequestPaused(
                "1",
                null,
                "frame1",
                "Document",
                null,
                200,
                "OK",
                responseHeaders,
                "network1",
                null
        );

        byte[] expected = (
                "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nCache-Control: no-cache\r\n\r\n"
        ).getBytes(StandardCharsets.US_ASCII);

        byte[] actual = formatResponseHeader(event, null);
        assertArrayEquals(expected, actual);
    }
}