package org.netpreserve.warcaroo;

import org.netpreserve.jwarc.*;
import org.netpreserve.warcaroo.cdp.BrowserProcess;
import org.netpreserve.warcaroo.cdp.NavigationException;
import org.netpreserve.warcaroo.cdp.RequestHandler;
import org.netpreserve.warcaroo.cdp.domains.Network;
import org.netpreserve.warcaroo.util.Url;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ExecutionException;

public class Renderer {
    private final static Logger log = LoggerFactory.getLogger(Renderer.class);
    private final Index index = new Index();

    public static void main(String[] args) throws Exception {
        Renderer renderer = new Renderer();
        renderer.renderWarc(Path.of(args[0]));
    }

    private RequestHandler.Response handleResourceRequest(Instant date, Network.Request request) {
        log.info("Resource request: {}", request);
        Index.Entry entry = index.findClosest(request.method(), request.url().toString(), date);
        log.info("Index entry: {}", entry);

        if (entry != null) {
            try (var reader1 = new WarcReader(entry.file())) {
                reader1.position(entry.position());
                var record1 = reader1.next().orElseThrow();
                var response1 = (WarcResponse) record1;
                return new RequestHandler.Response(response1.http().status(),
                        response1.http().reason(),
                        HttpHeaders.of(response1.http().headers().map(), (a, b) -> true),
                        response1.http().bodyDecoded().stream().readAllBytes());
            } catch (IOException e) {
                log.error("Error reading warc file", e);
            }
        }
        return null;
    }
    private void renderWarc(Path file) throws IOException {
        index.addWarc(file);

        try (var reader = new WarcReader(file);
             var browser = BrowserProcess.start()) {
            for (var record : reader) {
                if (record instanceof WarcResponse response) {
                    if (response.http().contentType().base().equals(MediaType.HTML)) {
                        renderPage(response, browser);
                    }
                }
            }
        }
    }

    private void renderPage(WarcResponse response, BrowserProcess browser) {
        log.info("Rendering {} at {}", response.target(), response.date());
        try (var window = browser.newWindow(null, r -> handleResourceRequest(response.date(), r))) {
            var navigation = window.navigateTo(new Url(response.target()));
            navigation.loadEvent().get();
            window.waitForRequestInterceptorIdle();
//            String html = window.eval("document.documentElement.outerHTML");
            String text = window.eval("document.documentElement.outerText");
            System.out.println(text);
        } catch (ExecutionException | NavigationException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
