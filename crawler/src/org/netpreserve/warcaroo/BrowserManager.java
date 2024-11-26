package org.netpreserve.warcaroo;

import org.netpreserve.warcaroo.cdp.BrowserProcess;
import org.netpreserve.warcaroo.cdp.Navigator;
import org.netpreserve.warcaroo.cdp.RequestHandler;
import org.netpreserve.warcaroo.cdp.ResourceFetched;
import org.netpreserve.warcaroo.cdp.domains.Browser;
import org.netpreserve.warcaroo.cdp.protocol.CDPTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Wrapper for BrowserProcess that restarts it upon crash.
 */
public class BrowserManager implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(BrowserManager.class);
    private final BrowserSettings browserSettings;
    private volatile BrowserProcess browserProcess;

    public BrowserManager(BrowserSettings browserSettings) throws IOException {
        this.browserSettings = browserSettings;
        start();
    }

    private synchronized void restart(Throwable reason) {
        log.warn("Restarting browser after crash.", reason);
        browserProcess.close();
        try {
            start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void start() throws IOException {
        browserProcess = BrowserProcess.start(
                browserSettings.executable(),
                browserSettings.options(),
                null,
                browserSettings.headless(),
                browserSettings.shell());
    }

    public Navigator newWindow(Consumer<ResourceFetched> resourceHandler, RequestHandler requestHandler) {
        return restartOnError(browserProcess -> browserProcess.newWindow(resourceHandler, requestHandler));
    }

    public Browser.Version version() {
        return restartOnError(BrowserProcess::version);
    }

    @Override
    public synchronized void close() throws IOException {
        browserProcess.close();
    }

    public <T> T restartOnError(Function<BrowserProcess, T> body) {
        try {
            return body.apply(browserProcess);
        } catch (UncheckedIOException e) {
            if (e.getCause() != null && e.getCause().getMessage().equalsIgnoreCase("Stream closed")) {
                restart(e);
                return body.apply(browserProcess);
            }
            throw e;
        } catch (CDPTimeoutException e) {
            restart(e);
            return body.apply(browserProcess);
        }
    }
}