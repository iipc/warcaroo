package org.netpreserve.warcaroo.webapp;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.netpreserve.jwarc.WarcDigest;
import org.netpreserve.warcaroo.*;
import org.netpreserve.warcaroo.cdp.NavigationException;
import org.netpreserve.warcaroo.cdp.domains.Browser;
import org.netpreserve.warcaroo.config.JobConfig;
import org.netpreserve.warcaroo.util.Url;
import org.netpreserve.warcaroo.webapp.OpenAPI.Doc;
import org.netpreserve.warcaroo.webapp.Route.GET;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URLConnection;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.netpreserve.warcaroo.webapp.Route.*;

public class Webapp implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(Webapp.class);
    static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .registerModule(new SimpleModule().addSerializer(WarcDigest.class, new JsonSerializer<WarcDigest>() {
                @Override
                public void serialize(WarcDigest value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                    gen.writeString(value.toString());
                }
            }));
    static final ObjectMapper JSON_NO_INDENT = JSON.copy().disable(SerializationFeature.INDENT_OUTPUT);
    private final Job job;
    private final Map<String, Route> routes = buildMap(this);
    private final OpenAPI openapi = new OpenAPI(routes);

    public Webapp(Job job) {
        this.job = job;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            var route = routes.get(exchange.getRequestURI().getPath());
            if (route != null) {
                route.handle(exchange);
            } else {
                notFound(exchange);
            }
        } catch (IllegalArgumentException e) {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(400, 0);
            exchange.getResponseBody().write((e.getMessage() + "\n").getBytes(UTF_8));
            e.printStackTrace();
        } catch (Exception e) {
            log.error("Error handling request " + exchange.getRequestURI(), e);
        } finally {
            exchange.close();
        }
    }

    @GET("/api")
    OpenAPI apidoc(HttpExchange exchange) throws IOException {
        return openapi;
    }

    public static class FrontierQuery extends Query {
        public Integer depth;
        public String rhost;
        public FrontierUrl.State state;

        void setHost(String host) {
            this.rhost = Url.reverseHost(host);
        }
    }

    @GET("/api/frontier")
    Paginated<FrontierUrl> frontier(FrontierQuery query) throws IOException {
        long count = job.db.frontier().count(query);
        var rows = job.db.frontier().query(query.orderBy(FrontierUrl.class), query);
        return new Paginated<>(count / query.limit + 1, count, rows);
    }

    public static class FrontierPaginated extends Paginated<FrontierUrl> {
        public final Map<String, Map<FrontierUrl.State, Long>> queueStateCounts;

        public FrontierPaginated(long lastPage, long lastRow, List<FrontierUrl> data,
                                 Map<String, Map<FrontierUrl.State, Long>> queueStateCounts) {
            super(lastPage, lastRow, data);
            this.queueStateCounts = queueStateCounts;
        }
    }

    @GET("/api/browsers")
    List<BrowserInfo> getBrowsers() {
        return job.browserManagers().stream()
                .map(BrowserInfo::new)
                .toList();
    }

    record BrowserInfo(Browser.Version version) {
        public BrowserInfo(BrowserManager browserManager) {
            this(browserManager.version());
        }
    }

    @GET("/api/config")
    JobConfig getConfig() {
        return job.config();
    }

    public static class HostsQuery extends Query {
        public String rhost;

        public void setHost(String host) {
            rhost = Url.reverseHost(host);
            // remove trailing "," if doing a glob
            if (host.startsWith("*")) {
                rhost = rhost.substring(0, rhost.length() - 1);
            }
        }
    }

    @GET("/api/hosts")
    Paginated<Host> hosts(HostsQuery query) {
        long count = job.db.hosts().count(query);
        var rows = job.db.hosts().queryHosts(query.orderBy(Host.class), query);
        return new Paginated(count / query.limit + 1, count, rows);
    }

    public static class PagesQuery extends Query {
        public Long hostId;
    }

    @GET("/api/pages")
    Paginated<Page> pages(PagesQuery query) throws IOException {
        long count = job.db.pages().count(query);
        var rows = job.db.pages().query(query, query.orderBy(Page.class), query.limit, (query.page - 1) * query.limit);
        return new Paginated(count / query.limit + 1, count, rows);
    }

    public static class ResourcesQuery extends Query {
        public String hostId;
        public String url;
        public String pageId;
    }

    @GET("/api/progress")
    Progress progress() {
        return job.progress();
    }

    @GET("/api/resources")
    Paginated<Resource> resources(ResourcesQuery query) throws IOException {
        long count = job.db.resources().count(query);
        var rows = job.db.resources().query(query.orderBy(Resource.class), query);
        return new Paginated(count / query.limit + 1, count, rows);
    }

    public static class RenderQuery {
        public Url url;
    }

    @GET("/api/render")
    void render(HttpExchange exchange, RenderQuery query) throws NavigationException, InterruptedException, IOException {
        var screenshot = Replay.render(job.db, job.browserManager(), query.url);
        exchange.getResponseHeaders().set("Content-Type", "image/webp");
        exchange.sendResponseHeaders(200, screenshot.length);
        exchange.getResponseBody().write(screenshot);
    }

    @GET("/api/workers")
    List<Worker.Info> render(HttpExchange exchange) {
        return job.workerInfo();
    }

    @GET("/")
    void home(HttpExchange exchange) throws IOException {
        var connection = Objects.requireNonNull(getClass().getResource("/META-INF/resources/main.html"), "Resource main.html")
                .openConnection();
        try (var stream = connection.getInputStream()){
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, connection.getContentLengthLong());
            stream.transferTo(exchange.getResponseBody());
        }
    }

    @GET("/events")
    void events(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        var writer = new OutputStreamWriter(exchange.getResponseBody(), UTF_8);
        double smoothingFactor = 0.05;
        double averageDownloadSpeed = 0;
        long lastTime = System.nanoTime();
        long lastBytes = 1; // FIXME
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            long time = System.nanoTime();
            long bytes = 1; // FIXME

            long elapsed = time - lastTime;
            long bytesDelta = bytes - lastBytes;

            double downloadSpeed = bytesDelta / (elapsed / 1000000000.0);
            averageDownloadSpeed = smoothingFactor * downloadSpeed + (1 - smoothingFactor) * averageDownloadSpeed;

            writer.write("event: progress\ndata: ");
            JSON_NO_INDENT.writeValue(writer, Map.of("speed", averageDownloadSpeed,
                    "state", job.state()));
            writer.write("\n\n");
            writer.flush();

            lastBytes = bytes;
            lastTime = time;
        }
    }

    @POST("/api/start")
    @Doc(summary = "Start the crawl")
    public void start() throws Job.BadStateException, IOException {
        job.start();
    }

    @POST("/api/stop")
    @Doc(summary = "Stop the crawl",
            value = "Waits for currently in progress page processing to complete. Once stopped the crawl can be " +
                    "started again later.")
    public void stop() throws Job.BadStateException {
        job.stop();
    }

    public record Sort(String field, Direction dir) {
        String sql(Map<String, String> columns) {
            var column = columns.get(field);
            if (column == null) throw new IllegalArgumentException("No column for field: " + field);
            return dir == Direction.DESC ? column + " DESC" : column;
        }

        public enum Direction {
            ASC, DESC;
        }
    }

    public record Filter(String field, String type, String value) {
    }


    public static class Paginated<T> {
        @JsonPropertyDescription("Index of the last page of results.")
        @Doc(example = "5")
        public final long last_page;
        @Doc(example = "101")
        public final long last_row;
        public final List<T> data;

        public Paginated(long lastPage, long lastRow, List<T> data) {
            last_page = lastPage;
            last_row = lastRow;
            this.data = data;
        }
    }

    private void notFound(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.contains("..")) throw new IllegalArgumentException();
        if (!path.contains(".")) path = path + ".html";

        var resource = getClass().getResource("/META-INF/resources" + path);
        if (resource != null) {
            var connection = resource.openConnection();
            try (var stream = connection.getInputStream()) {
                String type = path.endsWith(".mjs") ? "application/javascript" : URLConnection.guessContentTypeFromName(path);
                if (type != null) exchange.getResponseHeaders().set("Content-Type", type);
                if (path.startsWith("/webjars/")) {
                    exchange.getResponseHeaders().add("Cache-Control", "public, max-age=604800, immutable");
                }
                try (var out = Route.encodeResponse(exchange, 200, connection.getContentLengthLong())) {
                    stream.transferTo(out);
                }
                return;
            }
        }
        exchange.sendResponseHeaders(404, 0);
        exchange.getResponseBody().write("Not found".getBytes(UTF_8));
    }

}
