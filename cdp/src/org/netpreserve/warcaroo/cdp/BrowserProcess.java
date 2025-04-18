package org.netpreserve.warcaroo.cdp;

import org.netpreserve.warcaroo.cdp.domains.Browser;
import org.netpreserve.warcaroo.cdp.protocol.CDPClient;
import org.netpreserve.warcaroo.cdp.protocol.CDPClosedException;
import org.netpreserve.warcaroo.cdp.protocol.CDPSession;
import org.netpreserve.warcaroo.cdp.domains.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static java.lang.ProcessBuilder.Redirect.*;
import static java.util.stream.Collectors.joining;

/**
 * Launches and manages a browser process controlled via CDP.
 *
 * <p>Example usage:</p>
 * <pre>{@code
 *
 * try (BrowserProcess browserProcess = BrowserProcess.start();
 *      Navigator navigator = browserProcess.newWindow()) {
 *     navigator.navigateTo(new Url("http://example.com/"));
 * }
 * }</pre>
 */
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
    private Browser.Version version;

    private BrowserProcess(Process process, CDPClient cdp) {
        this.process = process;
        this.cdp = cdp;
        this.browser = cdp.domain(Browser.class);
        this.target = cdp.domain(Target.class);
    }

    public static BrowserProcess startHeadless(String executable, Path profileDir) throws IOException {
        return start(executable, List.of("--headless=new", "--disable-gpu"), profileDir, null);
    }

    public static BrowserProcess start(String executable, Path profileDir) throws IOException {
        return start(executable, null, profileDir, null);
    }

    public static BrowserProcess start(String executable, List<String> options, Path profileDir, List<String> shell) throws IOException {
        boolean deleteProfileOnExit = false;
        if (profileDir == null) {
            profileDir = Path.of("/tmp/warcaroo-" + UUID.randomUUID());
            deleteProfileOnExit = true;
        }
        String preferences = "{\"plugins\":{\"always_open_pdf_externally\": true}}";
        Path preferencesFile = profileDir.resolve("Default").resolve("Preferences");
        Process process;
        if (executable == null) {
            executable = probeForExecutable(shell);
        }
        if (shell == null) {
            shell = Files.exists(Path.of("/bin/sh")) ? List.of("/bin/sh", "-c") : null;
        }
        var command = new ArrayList<>(List.of(executable,
                shell != null ? "--remote-debugging-pipe" : "--remote-debugging-port=0",
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
                "--user-data-dir=" + profileDir,
                "--disable-blink-features=AutomationControlled",
                "--window-size=1920,1080"));
        if (options != null) {
            command.addAll(options);
        }
        if (shell != null) {
            // In pipe mode the browser expects to read CDP from FD 3 and write CDP to FD 4
            // we can't redirect arbitrary FDs in Java, so instead we use stdin and stdout
            // and get the shell to set the FDs up for us.
            //
            // We might be running remotely via SSH, so we write the preferences file via shell commands
            // rather than opening it directly.
            String escapedCommand = command.stream().map(BrowserProcess::singleQuote)
                    .collect(joining(" "));
            var shellCommand = new ArrayList<>(shell);
            String cleanupTrap = "";
            if (deleteProfileOnExit) {
                if (profileDir.toString().equals("/")) throw new IOException("Refusing to delete /");
                String deleteProfileCommand = "rm -rf " + singleQuote(profileDir.toString()) + " 2>/dev/null";
                cleanupTrap = "trap " + singleQuote(deleteProfileCommand) + " EXIT && ";
            }
            shellCommand.add(
                    cleanupTrap +
                    "mkdir -p " + singleQuote(preferencesFile.getParent().toString()) +
                    "&& echo " + singleQuote(preferences) + " > " + singleQuote(preferencesFile.toString()) +
                    "&& " + escapedCommand + " 3<&0 4>&1 0<&- 1>&2");
            process = new ProcessBuilder(shellCommand)
                    .redirectError(INHERIT)
                    .redirectOutput(PIPE)
                    .redirectInput(PIPE)
                    .start();
        } else {
            // if we don't have a shell available we can't do the file descriptor redirects
            // so we run CDP in socket mode
            Files.createDirectories(preferencesFile.getParent());
            Files.writeString(preferencesFile, preferences);
            process = new ProcessBuilder(command)
                    .inheritIO()
                    .redirectError(PIPE)
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
            return new BrowserProcess(process, shell != null ?
                    new CDPClient(process.getInputStream(), process.getOutputStream()) :
                    new CDPClient(readDevtoolsUrl(process)));
        } catch (Exception e) {
            process.destroy();
            throw e;
        }
    }

    private static String singleQuote(String string) {
        return "'" + string.replace("'", "'\\''") + "'";
    }

    private static String probeForExecutable(List<String> shell) throws IOException {
        if (shell != null) {
            var command = new ArrayList<String>();
            for (var executable : BROWSER_EXECUTABLES) {
                if (!command.isEmpty()) command.add("||");
                command.add("command");
                command.add("-v");
                command.add(singleQuote(executable));
            }
            var shellCommand = new ArrayList<>(shell);
            shellCommand.add(String.join(" ", command));
            var process = new ProcessBuilder(shellCommand)
                    .inheritIO()
                    .redirectOutput(PIPE)
                    .start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            try {
                if (process.waitFor() > 0) {
                    throw new IOException("Couldn't detect browser (shell: " + shell + "). Use the --browser option");
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return output;
        } else {
            for (var executable : BROWSER_EXECUTABLES) {
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

    /**
     * Closes the connection to the browser and terminates the process.
     */
    @Override
    public void close() {
        // try a graceful close command first
        try {
            browser.close();
            cdp.waitClose(Duration.ofMillis(1000));
        } catch (CDPClosedException e) {
            // browser's already gone. that's ok.
        } catch (Exception e) {
            log.warn("Error quitting browser", e);
        }
        // now close our end of the CDP connection
        try {
            cdp.close();
        } catch (Exception e) {
            log.warn("Error closing browser CDP connection", e);
        }
        // finally kill the process if it's still running
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

    /**
     * Opens a new browser window.
     */
    public Navigator newWindow(Consumer<ResourceFetched> resourceHandler,
                               RequestHandler requestHandler) {
        String targetId = target.createTarget("about:blank", true, 1920, 1080).targetId();
        var sessionId = target.attachToTarget(targetId, true).sessionId();
        var session = new CDPSession(cdp, sessionId, targetId);
        return new Navigator(session, resourceHandler, requestHandler);
    }

    public Browser.Version version() {
        if (version == null) {
            this.version = browser.getVersion();
        }
        return version;
    }
}
