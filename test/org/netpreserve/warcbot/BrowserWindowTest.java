package org.netpreserve.warcbot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BrowserWindowTest {

    @Test
    public void testLazyImagesAndScroll() throws IOException, InterruptedException {
        var requestedPaths = Collections.synchronizedSet(new HashSet<>());
        var httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (!path.equals("/favicon.ico")) {
                requestedPaths.add(path);
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
        try (var browserProcess = BrowserProcess.start(null);
             var browser = browserProcess.newWindow(recording -> {
            recordedPaths.add(URI.create(recording.url()).getPath());
            System.out.println("Got resource! " + recording);
        })) {
            httpServer.start();
            browser.navigateTo(new Url("http://127.0.0.1:" + port + "/"));
            assertEquals("Test page", browser.title());
            assertTrue(requestedPaths.contains("/"));

            assertEquals(List.of("/link1"), browser.extractLinks().stream()
                    .map(link -> link.toURI().getPath()).toList());

            browser.forceLoadLazyImages();
            Thread.sleep(1000);
            assertTrue(requestedPaths.contains("/lazy.jpg"));
//            browser.scrollToBottom();
//            assertEquals(Set.of("/", "/lazy.jpg", "/scroll.jpg"), requestedPaths);
        } finally {
            httpServer.stop(0);
        }
        assertTrue(recordedPaths.contains("/"));
        assertTrue(recordedPaths.contains("/lazy.jpg"));
        assertTrue(recordedPaths.contains("/post"));

    }

}