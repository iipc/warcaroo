package org.netpreserve.warcbot.webapp;

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
import org.netpreserve.warcbot.*;
import org.netpreserve.warcbot.cdp.NavigationException;
import org.netpreserve.warcbot.util.Url;
import org.netpreserve.warcbot.webapp.OpenAPI.Doc;
import org.netpreserve.warcbot.webapp.Route.GET;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URLConnection;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.netpreserve.warcbot.webapp.Route.*;

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
    private final Crawl crawl;
    private final Map<String, Route> routes = buildMap(this);
    private final OpenAPI openapi = new OpenAPI(routes);

    public Webapp(Crawl crawl) {
        this.crawl = crawl;
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
    Page<FrontierUrl> frontier(FrontierQuery query) throws IOException {
        long count = crawl.db.frontier().countFrontier(query);
        var rows = crawl.db.frontier().queryFrontier(query.orderBy(FrontierUrl.class), query);
        return new Page<>(count / query.limit + 1, count, rows);
    }

    public static class FrontierPage extends Page<FrontierUrl> {
        public final Map<String, Map<FrontierUrl.State, Long>> queueStateCounts;

        public FrontierPage(long lastPage, long lastRow, List<FrontierUrl> data,
                            Map<String, Map<FrontierUrl.State, Long>> queueStateCounts) {
            super(lastPage, lastRow, data);
            this.queueStateCounts = queueStateCounts;
        }
    }

    @GET("/api/config")
    Config getConfig() {
        return crawl.config();
    }

    public static class HostsQuery extends Query {
        public String rhost;

        public void setHost(String host) {
            rhost = Url.reverseHost(host);
        }
    }

    @GET("/api/crawlsettings")
    CrawlSettings getCrawlSettings() {
        return crawl.config().getCrawlSettings();
    }

    @PUT("/api/crawlsettings")
    void putCrawlSettings(CrawlSettings crawlSettings) {
        crawl.config().setCrawlSettings(crawlSettings);
    }

    @GET("/api/hosts")
    Page<StorageDAO.Host> hosts(HostsQuery query) {
        long count = crawl.db.storage().countHosts(query);
        var rows = crawl.db.storage().queryHosts(query.orderBy(StorageDAO.Host.class), query);
        return new Page(count / query.limit + 1, count, rows);
    }

    public static class PagesQuery extends Query {
        public Long hostId;
    }

    @GET("/api/pages")
    Page<StorageDAO.Page> pages(PagesQuery query) throws IOException {
        long count = crawl.db.storage().countPages(query);
        var rows = crawl.db.storage().queryPages(query, query.orderBy(StorageDAO.Page.class), query.limit, (query.page - 1) * query.limit);
        return new Page(count / query.limit + 1, count, rows);
    }

    public static class ResourcesQuery extends Query {
        public String hostId;
        public String url;
        public String pageId;
    }

    @GET("/api/resources")
    Page<Resource> resources(ResourcesQuery query) throws IOException {
        long count = crawl.db.storage().countResources(query);
        var rows = crawl.db.storage().queryResources(query.orderBy(Resource.class), query);
        return new Page(count / query.limit + 1, count, rows);
    }

    public static class RenderQuery {
        public Url url;
    }

    @GET("/api/render")
    void render(HttpExchange exchange, RenderQuery query) throws NavigationException, InterruptedException, IOException {
        var screenshot = Replay.render(crawl.db.storage(), crawl.browserProcess(), query.url);
        exchange.getResponseHeaders().set("Content-Type", "image/webp");
        exchange.sendResponseHeaders(200, screenshot.length);
        exchange.getResponseBody().write(screenshot);
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
                    "state", crawl.state()));
            writer.write("\n\n");
            writer.flush();

            lastBytes = bytes;
            lastTime = time;
        }
    }

    @POST("/api/start")
    @Doc(summary = "Start the crawl")
    public void start() throws Crawl.BadStateException {
        crawl.start();
    }

    @POST("/api/stop")
    @Doc(summary = "Stop the crawl",
            value = "Waits for currently in progress page processing to complete. Once stopped the crawl can be " +
                    "started again later.")
    public void stop() throws Crawl.BadStateException {
        crawl.stop();
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


    public static class Page<T> {
        @JsonPropertyDescription("Index of the last page of results.")
        @Doc(example = "5")
        public final long last_page;
        @Doc(example = "101")
        public final long last_row;
        public final List<T> data;

        public Page(long lastPage, long lastRow, List<T> data) {
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
