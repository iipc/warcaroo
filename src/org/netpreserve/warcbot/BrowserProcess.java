package org.netpreserve.warcbot;

import org.netpreserve.warcbot.cdp.CDPClient;
import org.netpreserve.warcbot.cdp.CDPSession;
import org.netpreserve.warcbot.cdp.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class BrowserProcess implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BrowserProcess.class);
    private static final List<String> BROWSER_EXECUTABLES = List.of(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
            "google-chrome-stable",
            "google-chrome",
            "chromium-browser"
    );

    private final Process process;
    private final CDPClient cdp;
    private final Target target;

    public BrowserProcess(Process process, CDPClient cdp) {
        this.process = process;
        this.cdp = cdp;
        this.target = cdp.domain(Target.class);
    }

    public static BrowserProcess start(String executable) throws IOException {
        for (var executableToTry : executable == null ? BROWSER_EXECUTABLES : List.of(executable)) {
            Process process;
            try {
                process = new ProcessBuilder(executableToTry,
//                        "--headless=new",
                        "--remote-debugging-port=0",
                        "--no-default-browser-check",
                        "--no-first-run",
                        "--ash-no-nudges",
                        "--disable-search-engine-choice-screen",
                        "--propagate-iph-for-testing",
                        "--disable-background-networking",
                        "--disable-sync",
                        "--use-mock-keychain",
                        "--user-data-dir=data/profile"
                )
                        .inheritIO()
                        .redirectError(ProcessBuilder.Redirect.PIPE)
                        .start();
            } catch (IOException e) {
                continue; // try another one
            }
            java.lang.Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
            try {
                return new BrowserProcess(process, new CDPClient(readDevtoolsUrl(process)));
            } catch (Exception e) {
                process.destroy();
                throw e;
            }
        }
        throw new IOException("Couldn't start browser");
    }

    private static URI readDevtoolsUrl(Process process) {
        var future = new CompletableFuture<URI>();
        var thread = new Thread(() -> {
            var prefix = "DevTools listening on ";
            try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                reader.lines().forEach(line -> {
                    if (line.startsWith(prefix)) {
                        future.complete(URI.create(line.substring(prefix.length())));
                    }
                    log.info("Browser: {}", line);
                });
            } catch (Exception e) {
                log.error("Error reading browser stderr", e);
                future.completeExceptionally(e);
            }
        }, "Browser stderr");
        thread.setDaemon(true);
        thread.start();
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        if (process != null) {
            process.destroy();
            try {
                process.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.warn("Interrupted closing process", e);
                Thread.currentThread().interrupt();
            } finally {
                process.destroyForcibly();
            }
        }
    }

    public BrowserWindow newWindow(Consumer<ResourceFetched> resourceHandler) {
        var targetId = target.createTarget("about:blank", true).targetId();
        var sessionId = target.attachToTarget(targetId, true).sessionId();
        var session = new CDPSession(cdp, sessionId);
        return new BrowserWindow(session, resourceHandler);
    }
}
