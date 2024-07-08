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
import java.util.logging.Level;

public class Browser implements AutoCloseable {
    private final Logger log = LoggerFactory.getLogger(Browser.class);
    public final ChromeDriver webDriver;

    static {
        // suppress noisy selenium logging
        java.util.logging.Logger.getLogger("org.openqa.selenium").setLevel(Level.WARNING);
    }

    public Browser() {
        var options = new ChromeOptions();
        options.addArguments("--headless");
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

    public void navigateToBlank() {
        webDriver.navigate().to("about:blank");
    }

    public Url currentUrl() {
        return new Url(webDriver.getCurrentUrl());
    }

    public String title() {
        return webDriver.getTitle();
    }
}
