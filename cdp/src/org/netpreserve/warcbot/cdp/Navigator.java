package org.netpreserve.warcbot.cdp;

import org.intellij.lang.annotations.Language;
import org.netpreserve.warcbot.cdp.domains.*;
import org.netpreserve.warcbot.cdp.domains.Runtime;
import org.netpreserve.warcbot.cdp.protocol.CDPException;
import org.netpreserve.warcbot.cdp.protocol.CDPSession;
import org.netpreserve.warcbot.cdp.protocol.CDPTimeoutException;
import org.netpreserve.warcbot.util.Url;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class Navigator implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Navigator.class);
    private final Emulation emulation;
    private final Page page;
    private final Runtime runtime;
    private final AtomicReference<Navigation> currentNavigation = new AtomicReference<>();
    private final IdleMonitor idleMonitor = new IdleMonitor();
    private final CDPSession cdpSession;
    private final RequestInterceptor requestInterceptor;
    private final Page.FrameTree frameTree;
    private volatile Runtime.ExecutionContextUniqueId isolatedContext;
    Duration pageLoadTimeout = Duration.ofSeconds(120);

    private void handleLifecycleEvent(Page.LifecycleEvent event) {
        var navigation = currentNavigation.get();
        if (navigation == null) return;
        if (!navigation.loaderId.equals(event.loaderId())) {
            log.warn("Ignore lifecycle event for unknown loader {}", event);
            return;
        }
        if (!navigation.frameId.equals(event.frameId())) return;
        navigation.handleLifecycleEvent(event);
    }

    public void setUserAgent(String userAgent) {
        emulation.setUserAgentOverride(userAgent);
    }

    public void block(Predicate<String> predicate) {
        requestInterceptor.block(predicate);
    }

    public record Navigation(
            Page.FrameId frameId,
            Network.LoaderId loaderId,
            CompletableFuture<Network.MonotonicTime> loadEvent) {
        Navigation(Page.FrameId frameId, Network.LoaderId loaderId) {
            this(frameId, loaderId, new CompletableFuture<>());
        }

        void completeExceptionally(Throwable t) {
            loadEvent.completeExceptionally(t);
        }

        void handleLifecycleEvent(Page.LifecycleEvent event) {
            if (event.name().equals("load")) {
                loadEvent.complete(event.timestamp());
            }
        }
    }

    public Navigator(CDPSession cdpSession,
                     Consumer<ResourceFetched> resourceHandler,
                     RequestHandler requestHandler,
                     Tracker tracker) {
        this.cdpSession = cdpSession;
        this.emulation = cdpSession.domain(Emulation.class);
        this.page = cdpSession.domain(Page.class);
        this.runtime = cdpSession.domain(Runtime.class);
        this.requestInterceptor = new RequestInterceptor(cdpSession, idleMonitor, tracker, requestHandler,
                resourceHandler, Path.of("data", "downloads"));

        page.onLifecycleEvent(this::handleLifecycleEvent);
        page.enable();
        this.frameTree = page.getFrameTree();
        runtime.onExecutionContextCreated(event -> {
            var context = event.context();
            if (context.auxData().frameId().equals(frameTree.frame().id()) && context.name().equals("warcbot")) {
                isolatedContext = context.uniqueId();
            }
            log.info("{}", event);
        });
        runtime.enable();
        page.setLifecycleEventsEnabled(true);
        page.createIsolatedWorld(frameTree.frame().id(), "warcbot", false);
        page.addScriptToEvaluateOnNewDocument("// warcbot", "warcbot");

        runtime.onConsoleAPICalled(event -> log.debug("Console: {} {}", event.type(), event.args()));
    }

    public static void main(String[] args) throws Exception {
        try (var browserProcess = BrowserProcess.start(null, Path.of("data", "profile"));
             var visitor = browserProcess.newWindow(resourceFetched -> {
                 System.out.println("Resource: " + resourceFetched);
             }, null, null)) {
            //browser.webDriver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
            visitor.navigateTo(new Url(args[0]));
            visitor.forceLoadLazyImages();
            System.err.println("Scrolling...");
            visitor.scrollToBottom();
            System.err.println("Done scrolling");
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public Navigation navigateTo(Url url) throws NavigationException, InterruptedException {
        requestInterceptor.reset();
        Page.Navigate result;
        try {
            // TODO: maybe change the proxy so that we can pass a specific timeout for this command
            result = page.navigate(url.toString());
        } catch (CDPTimeoutException e) {
            throw new NavigationTimedOutException(url, "Timed out waiting page.navigate()");
        }
        var navigation = new Navigation(result.frameId(), result.loaderId());
        var previousNavigation = currentNavigation.getAndSet(navigation);
        if (previousNavigation != null) {
            previousNavigation.completeExceptionally(new NavigationException(url, "Interrupted by navigateTo()"));
        }
        if (result.errorText() != null) {
            var e = new NavigationFailedException(url, result.errorText());
            navigation.completeExceptionally(e);
            throw e;
        }
        try {
            navigation.loadEvent().get(pageLoadTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            throw new NavigationTimedOutException(url, "Timed out waiting for load event");
        }
        return navigation;
    }

    @Override
    public void close() {
        try {
            page.close();
        } catch (CDPException e) {
            if (!e.getMessage().contains("Session with given id not found")) {
                throw e;
            }
        } catch (Exception e) {
            // ignore
        }
        cdpSession.close();
    }

    @SuppressWarnings("unchecked")
    public <T> T eval(@Language("JavaScript") String script) {
        var evaluate = runtime.evaluate(script, 2000, true, false, isolatedContext);
        if (evaluate.exceptionDetails() != null) {
            throw new RuntimeException(evaluate.exceptionDetails().toString());
        }
        return (T) evaluate.result().toJavaObject();
    }

    @SuppressWarnings("unchecked")
    public <T> T evalPromise(@Language("JavaScript") String script) {
        var evaluate = runtime.evaluate(script, 2000, true, true, isolatedContext);
        if (evaluate.exceptionDetails() != null) {
            throw new JSException(evaluate.exceptionDetails().toString());
        }
        return (T) evaluate.result().toJavaObject();
    }

    private static class JSException extends RuntimeException {

        public JSException(String message) {
            super(message);
        }
    }

    public List<Url> extractLinks() {
        List<String> urls = eval("""
                (function() {
                    const links = new Set();
                    for (const el of document.querySelectorAll('a[href]')) {
                        const href = el.href;
                        if (href.startsWith('http://') || href.startsWith('https://')) {
                            links.add(href.replace(/#.*$/, ''));
                        }
                    }
                    return Array.from(links);
                })();
                """);
        return urls.stream().map(Url::new).toList();
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
        try {
            evalPromise("""
                
                    new Promise((doneCallback, reject) => {
                const startTime = Date.now();
                const maxScrollTime = 5000; // 5 seconds timeout
                const scrollInterval = 50;

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
    
                    const scrollStep = document.scrollingElement.clientHeight * 0.2;

                    window.scrollBy({top: scrollStep, behavior: "instant"});
                    setTimeout(scroll, scrollInterval);
                }

                scroll();
                })
                """);
        } catch (JSException e) {
            log.warn("scrollToBottom threw {}", e.getMessage());
        }
    }

    public Navigation navigateToBlank() throws InterruptedException, TimeoutException, NavigationException {
        Navigation navigation = navigateTo(new Url("about:blank"));
        try {
            page.resetNavigationHistory();
        } catch (CDPException e) {
            // we might not be attached to a page
        }
        return navigation;
    }

    public void waitForLoadEvent() throws InterruptedException {
        try {
            currentNavigation.get().loadEvent().get(5, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            // ignore
        }
    }

    public void waitForRequestInterceptorIdle() throws InterruptedException {
        long start = System.currentTimeMillis();
        int n = idleMonitor.inflight;
        idleMonitor.waitUntilIdle();
        log.info("Waited {} ms for RequestInterceptor idle (if={})", System.currentTimeMillis() - start, n);
    }

    public Url currentUrl() {
        return new Url(eval("document.location.href"));
    }

    public String title() {
        return eval("document.title");
    }
}
