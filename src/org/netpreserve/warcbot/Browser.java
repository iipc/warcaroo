package org.netpreserve.warcbot;

import org.intellij.lang.annotations.Language;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.NetworkInterceptor;
import org.openqa.selenium.devtools.v126.network.Network;
import org.openqa.selenium.remote.http.Filter;
import org.openqa.selenium.remote.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class Browser implements AutoCloseable {
    private final Logger log = LoggerFactory.getLogger(Browser.class);
    public final ChromeDriver webDriver;

    static {
        // suppress noisy selenium logging
        java.util.logging.Logger.getLogger("org.openqa.selenium").setLevel(Level.WARNING);
    }

    public Browser(Config config) {
        var options = new ChromeOptions();
        options.addArguments("--headless");
        if (config.getBrowserBinary() != null) options.setBinary(config.getBrowserBinary());
        options.setCapability("webSocketUrl", true);
        this.webDriver = new ChromeDriver(options);
    }

    public void navigateTo(Url url) {
        webDriver.navigate().to(url.toString());
    }

    @Override
    public void close() {
        log.debug("Closing web driver");
        try {
            webDriver.quit();
        } catch (NoSuchSessionException e) {
            // ignore
        }
    }

    public NetworkInterceptor recordResources(Storage storage, UUID pageId) {
        var interceptor = new NetworkInterceptor(webDriver, (Filter) next -> request -> {
            HttpResponse response = next.execute(request);
            log.debug("Resource {} {} {}", pageId, response.getStatus(), request.getUri());
            if (response.getStatus() == 304) return response;
            try {
                storage.save(pageId, request, response);
            } catch (Exception e) {
                log.error("Error saving request", e);
            }
            return response;
        });
        // re-enable the cache, NetworkInterceptor turns it off
        webDriver.getDevTools().send(Network.setCacheDisabled(false));
        return interceptor;
    }

    @SuppressWarnings("unchecked")
    public <T> T eval(@Language("JavaScript") String script) {
        return (T)webDriver.executeScript(script);
    }

    public List<String> extractLinks() {
        return eval("""
                const links = new Set();
                for (const el of document.querySelectorAll('a[href]')) {
                    const href = el.href;
                    if (href.startsWith('http://') || href.startsWith('https://')) {
                        links.add(href.replace(/#.*$/, ''));
                    }
                }
                return Array.from(links);
                """);
    }

    public void forceLoadLazyImages() {
        eval("""
                    document.querySelectorAll('img[loading="lazy"]').forEach(img => {
                        img.loading = 'eager';
                        if (!img.complete) {
                          img.src = img.src;
                        }
                      });
                """);
    }

    public void scrollToBottom() {
        webDriver.executeAsyncScript("""
                const doneCallback = arguments[arguments.length - 1];
                const startTime = Date.now();
                const maxScrollTime = 5000; // 5 seconds timeout
                const scrollStep = window.innerHeight / 2; // Scroll half a viewport at a time
                const scrollInterval = 100; // Interval between scrolls in milliseconds
                
                function scroll() {
                    if (window.innerHeight + window.scrollY >= document.body.offsetHeight) {
                        // We've reached the bottom of the page
                        doneCallback();
                        return;
                    }
                
                    if (Date.now() - startTime > maxScrollTime) {
                        // We've exceeded the timeout
                        doneCallback();
                        return;
                    }
                
                    window.scrollBy(0, scrollStep);
                    setTimeout(scroll, scrollInterval);
                }
                
                scroll();
                """);
    }

    public void navigateToBlank() {
        webDriver.navigate().to("about:blank");
    }

    public Url currentUrl() {
        return new Url(webDriver.getCurrentUrl());
    }

    public String title() {
        return webDriver.getTitle();
    }

    public static void main(String[] args) {
        AtomicInteger counter = new AtomicInteger();
        try (var browser = new Browser(new Config());
             var ignored = new NetworkInterceptor(browser.webDriver, (Filter) next -> request -> {
                 HttpResponse response = next.execute(request);
                 System.out.println(counter.incrementAndGet() + " " + response.getStatus() + " " + request.getUri());
                 return response;
             })
        ) {
            //browser.webDriver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
            browser.navigateTo(new Url(args[0]));
            browser.forceLoadLazyImages();
            System.err.println("Scrolling...");
            browser.scrollToBottom();
            System.err.println("Done scrolling");
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
