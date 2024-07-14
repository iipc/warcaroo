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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static java.util.stream.Collectors.joining;

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
        boolean usePipe = Files.isExecutable(Path.of("/bin/sh"));
        for (var executableToTry : executable == null ? BROWSER_EXECUTABLES : List.of(executable)) {
            Process process;
            var command = List.of(executableToTry,
//                        "--headless=new",
                    usePipe ? "--remote-debugging-pipe" : "--remote-debugging-port=0",
//                        "--test-type",
                    "--no-default-browser-check",
                    "--no-first-run",
                    "--no-startup-window",
                    "--ash-no-nudges",
                    "--disable-search-engine-choice-screen",
                    "--propagate-iph-for-testing",
                    "--disable-background-networking",
                    "--disable-sync",
                    "--use-mock-keychain",
                    "--user-data-dir=data/profile",
                    "--disable-blink-features=AutomationControlled");
            try {
                if (usePipe) {
                    // in pipe mode the browser expects to read CDP from FD 3 and write CDP to FD 4
                    // we can't redirect arbitrary FDs in Java, so instead we use stdin and stdout
                    // and get the shell to set the FDs up for us.
                    String escapedCommand = command.stream().map(arg -> "'" + arg + "'").collect(joining(" "));
                    process = new ProcessBuilder("/bin/sh", "-c",
                            "exec " + escapedCommand + " 3<&0 4>&1 0<&- 1>&2")
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .redirectOutput(ProcessBuilder.Redirect.PIPE)
                            .redirectInput(ProcessBuilder.Redirect.PIPE)
                            .start();
                } else {
                    process = new ProcessBuilder(command)
                            .inheritIO()
                            .redirectError(ProcessBuilder.Redirect.PIPE)
                            .start();
                }
            } catch (IOException e) {
                continue; // try another one
            }
            java.lang.Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (process.waitFor(1, TimeUnit.SECONDS)) return;
                    process.destroy();
                    if (process.waitFor(10, TimeUnit.SECONDS)) return;
                    process.destroyForcibly();
                } catch (InterruptedException e) {
                    // just exit
                }
            }));
            try {
                return new BrowserProcess(process, usePipe ?
                        new CDPClient(process.getInputStream(), process.getOutputStream()) :
                        new CDPClient(readDevtoolsUrl(process)));
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

    public static BrowserProcess start() throws IOException {
        return start(null);
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
        return newWindow(resourceHandler, null);
    }

    public BrowserWindow newWindow(Consumer<ResourceFetched> resourceHandler, RequestInterceptor requestInterceptor) {
        String targetId = target.createTarget("about:blank", true).targetId();
        var sessionId = target.attachToTarget(targetId, true).sessionId();
        var session = new CDPSession(cdp, sessionId);
        return new BrowserWindow(session, resourceHandler, requestInterceptor);
    }
}
