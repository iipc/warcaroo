package org.netpreserve.warcbot;

import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

public class Replay {

    private static final Logger log = LoggerFactory.getLogger(Replay.class);

    public static void main(String[] args) throws IOException, InterruptedException, SQLException {
        try (var database = new Database(Path.of("data", "db.sqlite3"));
             var browserProcess = BrowserProcess.start();
             var window = browserProcess.newWindow(null, request -> {
                 var resource = database.storage().findResourceByUrl(request.url());
                 if (resource == null) {
                     return new RequestInterceptor.Response(404, "Not found");
                 }
                 try (var warcReader = new WarcReader(Path.of("data", resource.filename()))) {
                     warcReader.position(resource.responseOffset());
                     var record = (WarcResponse) warcReader.next().orElseThrow();
                     return new RequestInterceptor.Response(record.http());
                 } catch (IOException e) {
                     log.error("Error replaying {}", request.url(), e);
                     return new RequestInterceptor.Response(500, "Error");
                 }
             })) {
            window.navigateTo(new Url(args[0]));
            Thread.sleep(50000);
        }
    }
}
