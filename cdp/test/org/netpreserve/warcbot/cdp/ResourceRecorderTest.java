package org.netpreserve.warcbot.cdp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpreserve.warcbot.cdp.domains.Fetch;
import org.netpreserve.warcbot.cdp.domains.Network;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;

class ResourceRecorderTest {

    @Test
    public void testNormalRequest(@TempDir Path tempDir) throws Exception {
        var fetchId = new Fetch.RequestId("fetch-1");
        var networkId = new Network.RequestId("net-1");
        byte[] data = new byte[]{'h', 'i'};
        var request = new Network.Request("http://example/", null, "GET", new Network.Headers(), null);
        var timing = new Network.ResourceTiming(0);
        Network.Headers responseHeaders = new Network.Headers();
        responseHeaders.put("CONTENT-Type", "text/html");
        var response = new Network.Response(request.url(), 200, "OK", responseHeaders, "text/html",
                null, request.headers(), false, 1, "1.2.3.4", 1234,
                false, false, false, false, 8, timing,
                10000, "h2");
        var requestWillBeSentExtraInfo = new Network.RequestWillBeSentExtraInfo(networkId, List.of(), Map.of("User-Agent", "bob"));
        var responseReceivedExtraInfo = new Network.ResponseReceivedExtraInfo(networkId, Map.of(), 200, null);
        var requestPaused = new Fetch.RequestPaused(fetchId, request, null, null, null, 200,
                "OK", List.of(), networkId, null);
        var responseReceived = new Network.ResponseReceived(networkId, null, 0, null, response, null);
        var dataReceived = new Network.DataReceived(networkId, 0, data.length, data.length, data);
        var loadingFinished = new Network.LoadingFinished(networkId, 0, 5);

        var resources = new ArrayList<ResourceFetched>();
        var responseBodies = new ArrayList<byte[]>();
        var recorder = new ResourceRecorder(networkId, tempDir, resource -> {
            resources.add(resource);
            try {
                responseBodies.add(Channels.newInputStream(resource.responseBodyChannel()).readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, null);
        recorder.handleBufferedData(data);
        recorder.handleRequestWillBeSentExtraInfo(requestWillBeSentExtraInfo);
        recorder.handleResponseReceivedExtraInfo(responseReceivedExtraInfo);
        recorder.handleRequestPaused(requestPaused);
        recorder.handleResponseReceived(responseReceived);
        recorder.handleDataReceived(dataReceived);
        recorder.handleDataReceived(dataReceived);
        recorder.handleLoadingFinished(loadingFinished);

        assertTrue(isDirectoryEmpty(tempDir), "Temp file should be cleaned up");
        assertEquals(1, resources.size());

        var resource = resources.get(0);
        assertEquals(request.url(), resource.url());
        assertEquals(200, resource.status());
        assertEquals("h2", resource.protocol());
        assertEquals("GET / HTTP/1.1\r\nUser-Agent: bob\r\n\r\n",
                new String(resource.requestHeader(), US_ASCII));
        assertEquals("HTTP/1.1 200 OK\r\n" +
                     "CONTENT-Type: text/html\r\n\r\n", new String(resource.responseHeader(), US_ASCII));
        assertEquals("text/html", resource.responseType());
        assertEquals("1.2.3.4", resource.ipAddress());
        assertEquals("hihihi", new String(responseBodies.get(0), UTF_8));
    }

    public static boolean isDirectoryEmpty(Path directory) throws IOException {
        try (var dirStream = Files.newDirectoryStream(directory)) {
            return !dirStream.iterator().hasNext();
        }
    }

}