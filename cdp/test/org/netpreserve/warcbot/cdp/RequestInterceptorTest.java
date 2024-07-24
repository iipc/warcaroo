package org.netpreserve.warcbot.cdp;

import org.junit.jupiter.api.Test;
import org.netpreserve.warcbot.cdp.domains.Fetch;
import org.netpreserve.warcbot.cdp.domains.Network;
import org.netpreserve.warcbot.cdp.domains.Page;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.netpreserve.warcbot.cdp.RequestInterceptor.formatResponseHeader;

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

        Map<String, String> extraInfoHeaders = new LinkedHashMap<>();
        extraInfoHeaders.put(":authority", "example.com");
        extraInfoHeaders.put("Accept-Language", "en-US");

        var expected = (
                "GET /test HTTP/1.1\r\n" +
                "host: example.com\r\n" +
                "Accept-Language: en-US\r\n\r\n"
        );

        byte[] actual = RequestInterceptor.formatRequestHeader(request, extraInfoHeaders);
        assertEquals(expected, new String(actual, US_ASCII));
    }

    @Test
    void testFormatRequestHeaderWithoutExtraInfoHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "text/html");
        headers.put("User-Agent", "MyCrawler/1.0");
        var request = new Network.Request(
                "http://example.com/test",
                null,
                "GET",
                headers,
                null
        );

        String expected = (
                "GET /test HTTP/1.1\r\n" +
                "Accept: text/html\r\n" +
                "User-Agent: MyCrawler/1.0\r\n" +
                "Host: example.com\r\n\r\n"
        );

        byte[] actual = RequestInterceptor.formatRequestHeader(request, null);
        assertEquals(expected, new String(actual, US_ASCII));
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
                new Fetch.RequestId("1"),
                null,
                new Page.FrameId("frame1"),
                "Document",
                null,
                200,
                "OK",
                responseHeaders,
                new Network.RequestId("network1"),
                null
        );

        byte[] expected = (
                "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nCache-Control: no-cache\r\n\r\n"
        ).getBytes(StandardCharsets.US_ASCII);

        byte[] actual = formatResponseHeader(event, null);
        assertArrayEquals(expected, actual);
    }
}