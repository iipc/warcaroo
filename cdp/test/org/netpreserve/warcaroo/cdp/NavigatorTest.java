package org.netpreserve.warcaroo.cdp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpreserve.warcaroo.util.Url;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class NavigatorTest {
    private static final Logger log = LoggerFactory.getLogger(NavigatorTest.class);
    private static BrowserProcess browserProcess;
    private static Path profileDir;

    @BeforeAll
    public static void setUp(@TempDir Path tempDir) throws IOException {
        profileDir = tempDir.resolve("profile");
        browserProcess = BrowserProcess.start(null, profileDir, true);
    }

    @AfterAll
    static void tearDown() throws InterruptedException {
        browserProcess.close();
        // wait for browser child processes to exit is there a better way to do this?)
        // otherwise deleting the tempdir fails because files are still open/locked
        Thread.sleep(100);
    }

    @Test
    void testRedirect() throws Exception {
        var httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0, "/", exchange -> {
            switch (exchange.getRequestURI().toString()) {
                case "/redirect1" -> {
                    exchange.getResponseHeaders().add("Location", "/redirect2");
                    byte[] body = "r1 body".getBytes();
                    exchange.sendResponseHeaders(301, body.length);
                    exchange.getResponseBody().write(body);
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
                var responseHeader = new String(resource.responseHeader(), StandardCharsets.US_ASCII);
                var matcher = Pattern.compile("(?mi)^Content-Length: ([0-9]+)$").matcher(responseHeader);
                assertTrue(matcher.find());
                var contentLength = Integer.parseInt(matcher.group(1));
                assertEquals(0, contentLength, "content-length should be zero as browser removes body");
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

            // Send the body gzipped to test our handling of Content-Encoding
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            var out = new ByteArrayOutputStream();
            try (var gzipStrem = new GZIPOutputStream(out)) {
                gzipStrem.write("""
                        <!doctype html>
                        <title>Test page</title>
                        <style>.unused { background: url(bg.jpg) }</style>
                        <div style='height:4000px'></div>
                        <a href='link1'>Link1</a>
                        <svg><a href='svglink'><text y='25'>Svglink</text></a></svg>
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
            }
            exchange.sendResponseHeaders(200, out.size());
            exchange.getResponseBody().write(out.toByteArray());
            exchange.close();
        });
        int port = httpServer.getAddress().getPort();

        var recordedPaths = new HashSet<String>();
        try (var navigator = browserProcess.newWindow(recording -> {
            recordedPaths.add(recording.url().path());
            try {
                long bodySize = recording.responseBodyChannel().size();
                System.out.println("Got resource! " + recording + " " + bodySize + " bytes");
            } catch (IOException e) {
                log.error("Error getting response body size", e);
            }

        }, null)) {

            navigator.setUserAgent("test-agent");

            String title = navigator.title();
            navigator.navigateToBlank();
//            browser.fetchResource(new Url("http://127.0.0.1:" + port + "/robots.txt"));
            title = navigator.title();

            httpServer.start();


            String baseUrl = "http://127.0.0.1:" + port + "/";
            var navigation = navigator.navigateTo(new Url(baseUrl));
            navigation.loadEvent().get(10, TimeUnit.SECONDS);
            var resource = navigation.mainResource().get(10, TimeUnit.SECONDS);

            long bodySize = resource.responseBodyChannel().size();
            String header = new String(resource.responseHeader(), StandardCharsets.US_ASCII);
            Matcher matcher = Pattern.compile("(?mi)^Content-Length:\\s*(\\d+)$").matcher(header);
            long contentLength = matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
            assertEquals(bodySize, contentLength,
                    "Content-Length header should be adjusted to match body size");
            assertFalse(header.toLowerCase(Locale.ROOT).contains("Content-Encoding:"),
                    "Content-Encoding header should have been removed");

            title = navigator.title();

            assertEquals("Test page", navigator.title());
            assertTrue(requestedPaths.contains("/"));

            assertEquals(List.of("/link1", "/svglink"), navigator.extractLinks().stream()
                    .map(link -> link.path()).toList());

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

        System.out.println(Files.list(profileDir));

    }

    @Test
    public void testNavigationLock() throws Exception {
        var httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0, "/", exchange -> {
            if (exchange.getRequestURI().toString().equals("/")) {
                exchange.getResponseHeaders().add("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, 0);
                var html = """
                        <!doctype html>
                        <title>Hello</title>
                        
                        <script>
                            location.href = '/stop-me';
                        </script>
                        """;
                exchange.getResponseBody().write(html.getBytes());
            } else {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("oh no".getBytes());
            }
            exchange.close();
        });
        httpServer.start();
        try (var navigator = browserProcess.newWindow(res -> {}, null)){

            var navigationUrls = new ArrayList<Url>();
            navigator.setNavigationHandler((url, reason) -> {
                navigationUrls.add(url);
                return false;
            });
            navigator.navigateTo(new Url("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/")).loadEvent().get();

            assertFalse(navigationUrls.isEmpty());
            assertEquals("/stop-me", navigationUrls.get(0).path());
            assertEquals("/", navigator.currentUrl().path());

        } finally {
            httpServer.stop(0);
        }
    }

}