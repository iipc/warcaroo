package org.netpreserve.warcbot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BrowserTest {

    @Test
    public void testLazyImagesAndScroll() throws IOException {
        var requestedPaths = Collections.synchronizedSet(new HashSet<>());
        var httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("""
                <title>Test page</title>
                <div style='height:4000px'></div>
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
                </script>
                """.getBytes());
            exchange.close();
        });
        int port = httpServer.getAddress().getPort();

        try (var browser = new Browser(new Config())) {
            httpServer.start();
            browser.navigateTo(new Url("http://127.0.0.1:" + port + "/"));
            assertEquals("Test page", browser.title());
            assertEquals(Set.of("/"), requestedPaths);
            browser.forceLoadLazyImages();
            assertEquals(Set.of("/", "/lazy.jpg"), requestedPaths);
            browser.scrollToBottom();
            assertEquals(Set.of("/", "/lazy.jpg", "/scroll.jpg"), requestedPaths);
        } finally {
            httpServer.stop(0);
        }
    }

}