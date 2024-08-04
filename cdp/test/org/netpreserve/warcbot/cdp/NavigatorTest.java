package org.netpreserve.warcbot.cdp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpreserve.warcbot.util.Url;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class NavigatorTest {
    private static final Logger log = LoggerFactory.getLogger(NavigatorTest.class);
    private static BrowserProcess browserProcess;

    @BeforeAll
    public static void setUp(@TempDir Path tempDir) throws IOException {
        browserProcess = BrowserProcess.start(null, tempDir.resolve("profile"), true);
    }

    @AfterAll
    static void tearDown() {
        browserProcess.close();
    }

    @Test
    void testRedirect() throws Exception {
        var httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0, "/", exchange -> {
            switch (exchange.getRequestURI().toString()) {
                case "/redirect1" -> {
                    exchange.getResponseHeaders().add("Location", "/redirect2");
                    exchange.sendResponseHeaders(301, 0);
                    exchange.getResponseBody().write("r1 body".getBytes());
                }
                case "/redirect2" -> {
                    exchange.getResponseHeaders().add("Location", "/end");
                    exchange.sendResponseHeaders(301, 0);
                    exchange.getResponseBody().write("r2 body".getBytes());
                }
                case "/end" -> {
                    exchange.getResponseHeaders().add("Content-Type", "text/html");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write("Arrived".getBytes());
                }
            }
            exchange.close();
        });
        httpServer.start();
        var subresources = new ArrayList<ResourceFetched>();
        try (var navigator = browserProcess.newWindow(subresources::add, null)){
            var navigation = navigator.navigateTo(new Url("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/redirect1"));
            navigator.networkManager().waitForLoadingResources();

            assertEquals(2, subresources.size());
            {
                var resource = subresources.get(0);
                assertEquals("/redirect1", resource.url().path());
                assertEquals("/redirect2", resource.redirect());
            }
            {
                var resource = subresources.get(1);
                assertEquals("/redirect2", resource.url().path());
                assertEquals("/end", resource.redirect());
            }
            {
                var resource = navigation.mainResource().get(5, TimeUnit.SECONDS);
                assertEquals("/end", resource.url().path());
                assertNull(resource.redirect());
                assertEquals("Arrived", new String(Channels.newInputStream(resource.responseBodyChannel()).readAllBytes()));
            }
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    public void testLazyImagesAndScroll(@TempDir Path tempDir) throws IOException, InterruptedException, ExecutionException, TimeoutException, NavigationException {
        var requestedPaths = Collections.synchronizedSet(new HashSet<>());
        var httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            log.info("{} {}", exchange.getRequestMethod(), path);
            if (!path.equals("/favicon.ico")) {
                requestedPaths.add(path);
            }
            if (path.equals("/download")) {
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("Download me".getBytes());
                exchange.close();
                return;
            } else if (path.equals("/redirect1")) {
                exchange.getResponseHeaders().add("Location", "/redirect2");
                exchange.sendResponseHeaders(301, -1);
                exchange.close();
                return;
            } else if (path.equals("/redirect2")) {
                exchange.getResponseHeaders().add("Location", "/redirectend");
                exchange.sendResponseHeaders(301, -1);
                exchange.close();
                return;
            } else if (path.equals("/redirectend")) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("Arrived".getBytes());
                exchange.close();
                return;
            }

            assertEquals("test-agent", exchange.getRequestHeaders().getFirst("User-Agent"));

            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.getResponseHeaders().add("Test", "1");
            exchange.getResponseHeaders().add("Test", "2");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("""
                <!doctype html>
                <title>Test page</title>
                <style>.unused { background: url(bg.jpg) }</style>
                <div style='height:4000px'></div>
                <a href='link1'>Link1</a>
                <img loading=lazy src=lazy.jpg id=lazy>
                <img id=scrollImg data-src=scroll.jpg width=50 height=50>
                <img src=srcset1.jpg srcset="srcset2.jpg 100w, srcset3.jpg 200w">
                <script>
                    var observer = new IntersectionObserver(function(entries, observer) {
                        entries.forEach(function(entry) {
                            if (entry.isIntersecting) {
                                const img = entry.target;
                                img.src = img.dataset.src;
                                observer.unobserve(img);
                            }
                        });
                    });
                    observer.observe(document.getElementById('scrollImg'));
                    observer.observe(document.getElementById('lazy'));
    
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
        try (var navigator = browserProcess.newWindow(recording -> {
            recordedPaths.add(recording.url().path());
            System.out.println("Got resource! " + recording);
        }, null)) {

            navigator.setUserAgent("test-agent");

            String title = navigator.title();
            navigator.navigateToBlank();
//            browser.fetchResource(new Url("http://127.0.0.1:" + port + "/robots.txt"));
            title = navigator.title();

            httpServer.start();


            String baseUrl = "http://127.0.0.1:" + port + "/";
            navigator.navigateTo(new Url(baseUrl)).loadEvent().get(10, TimeUnit.SECONDS);
            title = navigator.title();

            assertEquals("Test page", navigator.title());
            assertTrue(requestedPaths.contains("/"));

            assertEquals(List.of("/link1"), navigator.extractLinks().stream()
                    .map(link -> link.toURI().getPath()).toList());

            Thread.sleep(1000);
            assertTrue(requestedPaths.contains("/lazy.jpg"));
            navigator.scrollToBottom();
            navigator.waitForRequestInterceptorIdle();
            assertTrue(requestedPaths.contains("/scroll.jpg"));

            try {
                navigator.navigateTo(new Url(baseUrl + "download"));
            } catch (NavigationFailedException e) {
                assertEquals("net::ERR_ABORTED", e.errorText(),
                        "Download should abort the page load");
            }
            navigator.waitForRequestInterceptorIdle();
        } finally {
            httpServer.stop(0);
        }
        assertTrue(recordedPaths.contains("/bg.jpg"));
        assertTrue(recordedPaths.contains("/srcset1.jpg"));
        assertTrue(recordedPaths.contains("/srcset2.jpg"));
        assertTrue(recordedPaths.contains("/srcset3.jpg"));
        assertTrue(recordedPaths.contains("/lazy.jpg"));
        assertTrue(recordedPaths.contains("/post"));
        assertTrue(recordedPaths.contains("/download"));
    }

    @Test
    public void testPDF() throws Exception {
        var httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0, "/", exchange -> {
            if (exchange.getRequestURI().toString().equals("/test.pdf")) {
                exchange.getResponseHeaders().add("Content-Type", "application/pdf");
                exchange.sendResponseHeaders(200, 0);
                String pdf = """
                        %PDF-1.0
                        1 0 obj<</Pages 2 0 R>>endobj 2 0 obj<</Kids[3 0 R]/Count 1>>endobj 3 0 obj<</MediaBox[0 0 3 3]>>endobj
                        trailer<</Root 1 0 R>>""";
                exchange.getResponseBody().write(pdf.getBytes());
            }
            exchange.close();
        });
        httpServer.start();
        var subresources = new ArrayList<ResourceFetched>();
        try (var navigator = browserProcess.newWindow(subresources::add, null)){

            try {
                navigator.navigateTo(new Url("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/test.pdf"));
            } catch (NavigationFailedException ignored) {
                // navigation should be aborted as we transition to download
            }
            navigator.waitForRequestInterceptorIdle();
            navigator.networkManager().waitForLoadingResources();

            assertEquals(1, subresources.size());
        } finally {
            httpServer.stop(0);
        }
    }

}