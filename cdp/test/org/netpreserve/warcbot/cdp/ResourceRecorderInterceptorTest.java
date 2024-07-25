package org.netpreserve.warcbot.cdp;

import org.junit.jupiter.api.Test;
import org.netpreserve.warcbot.cdp.domains.Network;

import java.util.LinkedHashMap;
import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ResourceRecorderInterceptorTest {
    @Test
    void testFormatRequestHeaderWithExtraInfoHeaders() {
        var headers = new Network.Headers();
        headers.put("Accept", "text/html");
        headers.put("User-Agent", "MyCrawler/1.0");
        var request = new Network.Request(
                "http://example.com/test",
                null,
                "GET",
                headers,
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

        byte[] actual = ResourceRecorder.formatRequestHeader(request, extraInfoHeaders);
        assertEquals(expected, new String(actual, US_ASCII));
    }

    @Test
    void testFormatRequestHeaderWithoutExtraInfoHeaders() {
        var headers = new Network.Headers();
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

        byte[] actual = ResourceRecorder.formatRequestHeader(request, null);
        assertEquals(expected, new String(actual, US_ASCII));
    }
}