package org.netpreserve.warcbot.cdp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpreserve.warcbot.NavigationException;
import org.netpreserve.warcbot.util.Url;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class NavigatorTest {

    @Test
    public void testLazyImagesAndScroll(@TempDir Path tempDir) throws IOException, InterruptedException, ExecutionException, TimeoutException, NavigationException {
        var requestedPaths = Collections.synchronizedSet(new HashSet<>());
        var httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (!path.equals("/favicon.ico")) {
                requestedPaths.add(path);
            }
            if (path.equals("/download")) {
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("Download me".getBytes());
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.getResponseHeaders().add("Test", "1");
            exchange.getResponseHeaders().add("Test", "2");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("""
                <title>Test page</title>
                <div style='height:4000px'></div>
                <a href='link1'>Link1</a>
                <img loading=lazy src=lazy.jpg>
                <img id=scrollImg data-src=scroll.jpg>
                <script>
                    new IntersectionObserver(function(entries, observer) {
                        entries.forEach(function(entry) {
                            if (entry.isIntersecting) {
                                const img = entry.target;
                                img.src = img.dataset.src;
                                observer.unobserve(img);
                            }
                        });
                    }).observe(document.getElementById('scrollImg'));
    
                    const headers = new Headers();
                    headers.append("Test-Header", 1);
                    headers.append("Test-Header", 2);
                    const body = new Int8Array(8);
                    body[0] = 0xc3;
                    body[1] = 0x28;
    
                    fetch("/post", {method: "POST", headers: headers, body: body});
                </script>
                """.getBytes());
            exchange.close();
        });
        int port = httpServer.getAddress().getPort();

        var recordedPaths = new HashSet<String>();
        try (var browserProcess = BrowserProcess.start(null, tempDir.resolve("profile"));
             var browser = browserProcess.newWindow(recording -> {
            recordedPaths.add(URI.create(recording.url()).getPath());
            System.out.println("Got resource! " + recording);
        }, null, null)) {
            String title = browser.title();
            browser.navigateToBlank();
//            browser.fetchResource(new Url("http://127.0.0.1:" + port + "/robots.txt"));
            title = browser.title();

            httpServer.start();


            String baseUrl = "http://127.0.0.1:" + port + "/";
            browser.navigateTo(new Url(baseUrl)).loadEvent().get(10, TimeUnit.SECONDS);
            title = browser.title();

            assertEquals("Test page", browser.title());
            assertTrue(requestedPaths.contains("/"));

            assertEquals(List.of("/link1"), browser.extractLinks().stream()
                    .map(link -> link.toURI().getPath()).toList());

            browser.forceLoadLazyImages();
            Thread.sleep(1000);
            assertTrue(requestedPaths.contains("/lazy.jpg"));
            browser.scrollToBottom();
            browser.waitForRequestInterceptorIdle();
            assertTrue(requestedPaths.contains("/scroll.jpg"));

            try {
                browser.navigateTo(new Url(baseUrl + "download"));
            } catch (NavigationException e) {
                assertEquals("net::ERR_ABORTED", e.getMessage(),
                        "Download should abort the page load");
            }
            Thread.sleep(10000);
        } finally {
            httpServer.stop(0);
        }
        assertTrue(recordedPaths.contains("/"));
        assertTrue(recordedPaths.contains("/lazy.jpg"));
        assertTrue(recordedPaths.contains("/post"));

    }

}