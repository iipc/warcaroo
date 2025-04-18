package org.netpreserve.warcaroo;

import org.netpreserve.jwarc.HttpResponse;
import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.warcaroo.cdp.BrowserProcess;
import org.netpreserve.warcaroo.cdp.NavigationException;
import org.netpreserve.warcaroo.cdp.RequestHandler;
import org.netpreserve.warcaroo.cdp.ResourceFetched;
import org.netpreserve.warcaroo.util.Url;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class Replay {

    private static final Logger log = LoggerFactory.getLogger(Replay.class);

    public static RequestHandler.Response toResponse(HttpResponse httpResponse) throws IOException {
        return new RequestHandler.Response(httpResponse.status(), httpResponse.reason(),
                HttpHeaders.of(httpResponse.headers().map(), (name, value) -> true),
                httpResponse.body().stream().readAllBytes());
    }

    public static void main(String[] args) throws IOException, InterruptedException, SQLException, TimeoutException, NavigationException {
        try (var database = Database.open(Path.of("data", "db.sqlite3"));
             var browserProcess = BrowserProcess.start();
             var window = browserProcess.newWindow(null, request -> {
                 var resource = database.resources().findByUrl(request.url());
                 if (resource == null) {
                     return new RequestHandler.Response(404, "Not found");
                 }
                 try (var warcReader = new WarcReader(Path.of("data", resource.filename()))) {
                     warcReader.position(resource.responseOffset());
                     var record = (WarcResponse) warcReader.next().orElseThrow();
                     return toResponse(record.http());
                 } catch (IOException e) {
                     log.error("Error replaying {}", request.url(), e);
                     return new RequestHandler.Response(500, "Error");
                 }
             })) {
            window.navigateTo(new Url(args[0]));
            Thread.sleep(50000);
        }
    }

    public static byte[] render(Database db, BrowserManager browserManager, Url url) throws NavigationException, InterruptedException {
        try (var window = browserManager.newWindow(new Consumer<ResourceFetched>() {
            @Override
            public void accept(ResourceFetched resourceFetched) {
                log.info("Fetched {}", resourceFetched);
            }
        }, request -> {
            var resource = db.resources().findByUrl(request.url());
            if (resource == null) {
                return new RequestHandler.Response(404, "Not found");
            }
            try (var warcReader = new WarcReader(Path.of("data", resource.filename()))) {
                warcReader.position(resource.responseOffset());
                var record = (WarcResponse) warcReader.next().orElseThrow();
                return toResponse(record.http());
            } catch (IOException e) {
                log.error("Error replaying {}", request.url(), e);
                return new RequestHandler.Response(500, "Error");
            }
        })) {
            window.networkManager().captureResponseBodies(false);
            window.navigateTo(url);
            window.waitForRequestInterceptorIdle();
            return window.screenshot();
        }
    }
}
