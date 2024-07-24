package org.netpreserve.warcbot.cdp;

import org.netpreserve.warcbot.cdp.domains.Browser;
import org.netpreserve.warcbot.cdp.protocol.CDPClient;
import org.netpreserve.warcbot.cdp.protocol.CDPSession;
import org.netpreserve.warcbot.cdp.domains.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
            "chromium-browser",
            "google-chrome",
            "google-chrome-stable",
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe");

    private final Process process;
    private final CDPClient cdp;
    private final Browser browser;
    private final Target target;

    public BrowserProcess(Process process, CDPClient cdp) {
        this.process = process;
        this.cdp = cdp;
        this.browser = cdp.domain(Browser.class);
        this.target = cdp.domain(Target.class);
    }

    public static BrowserProcess start(String executable, Path profileDir) throws IOException {
        return start(executable, profileDir, false);
    }

    public static BrowserProcess start(String executable, Path profileDir, boolean headless) throws IOException {
        boolean usePipe = Files.isExecutable(Path.of("/bin/sh"));
        Process process;
        if (executable == null) {
            executable = probeForExecutable();
        }
        var command = new ArrayList<>(List.of(executable,
                usePipe ? "--remote-debugging-pipe" : "--remote-debugging-port=0",
                "--no-default-browser-check",
                "--no-first-run",
                "--no-startup-window",
                "--ash-no-nudges",
                "--disable-search-engine-choice-screen",
                "--propagate-iph-for-testing",
                "--disable-background-networking",
                "--disable-background-time-throttling",
                "--disable-backgrounding-occluded-windows",
                "--disable-renderer-backgrounding",
                "--disable-sync",
                "--use-mock-keychain",
                "--user-data-dir=" + profileDir.toString(),
                "--disable-blink-features=AutomationControlled"));
        if (headless) {
            command.add("--headless=new");
            command.add("--disable-gpu");
        }
        if (usePipe) {
            // in pipe mode the browser expects to read CDP from FD 3 and write CDP to FD 4
            // we can't redirect arbitrary FDs in Java, so instead we use stdin and stdout
            // and get the shell to set the FDs up for us.
            String escapedCommand = command.stream()
                    .map(arg -> "'" + arg.replace("'", "'\\''") + "'")
                    .collect(joining(" "));
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

    private static String probeForExecutable() throws IOException {
        for (var executable : BROWSER_EXECUTABLES){
            try {
                new ProcessBuilder(executable, "--version")
                        .inheritIO()
                        .start();
                return executable;
            } catch (IOException e) {
                // try next one
            }
        }
        throw new IOException("Couldn't detect browser. Use the --browser option");
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
        return start(null, Path.of("data", "profile"));
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

    public Navigator newWindow(Consumer<ResourceFetched> resourceHandler,
                               RequestHandler requestHandler,
                               Tracker tracker) {
        String targetId = target.createTarget("about:blank", true).targetId();
        var sessionId = target.attachToTarget(targetId, true).sessionId();
        var session = new CDPSession(cdp, sessionId);
        return new Navigator(session, resourceHandler, requestHandler, tracker);
    }
}
