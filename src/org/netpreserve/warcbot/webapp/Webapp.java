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
import org.netpreserve.warcbot.Candidate;
import org.netpreserve.warcbot.Resource;
import org.netpreserve.warcbot.StorageDAO;
import org.netpreserve.warcbot.Crawl;
import org.netpreserve.warcbot.webapp.OpenAPI.Doc;
import org.netpreserve.warcbot.webapp.Route.GET;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URLConnection;
import java.util.*;
import java.util.stream.Collectors;

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

    @GET("/api/frontier")
    FrontierPage frontier(FrontierQuery query) throws IOException {
        long count = crawl.db.storage().countPages();
        var candidates = crawl.db.frontier().queryFrontier(query.orderBy(), query.size, (query.page - 1) * query.size);
        var queueNames = candidates.stream().map(Candidate::queue).collect(Collectors.toSet());
        return new FrontierPage(count / query.size + 1, count, candidates,
                crawl.db.frontier().getQueueStateCounts(queueNames));
    }

    public static class FrontierPage extends Page<Candidate> {
        public final Map<String, Map<Candidate.State, Long>> queueStateCounts;

        public FrontierPage(long lastPage, long lastRow, List<Candidate> data,
                            Map<String, Map<Candidate.State, Long>> queueStateCounts) {
            super(lastPage, lastRow, data);
            this.queueStateCounts = queueStateCounts;
        }
    }

    @GET("/api/pages")
    Page<StorageDAO.PageExt> pages(PagesQuery query) throws IOException {
        long count = crawl.db.storage().countPages();
        return new Page(count / query.size + 1, count,
                crawl.db.storage().queryPages(query.orderBy(), query.size, (query.page - 1) * query.size));
    }

    @GET("/api/resources")
    Page<Resource> resources(ResourcesQuery query) throws IOException {
        long count = crawl.db.storage().countResources();
        return new Page(count / query.size + 1, count,
                crawl.db.storage().queryResources(query.orderBy(), query.size, (query.page - 1) * query.size));
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
        long lastBytes = crawl.tracker.downloadedBytes();
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            long time = System.nanoTime();
            long bytes = crawl.tracker.downloadedBytes();

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

    abstract static class BaseQuery {
        @Doc("Page number of results to return. Starting from 1.")
        public long page = 1;
        @Doc("How many results to return per page.")
        public int size = 10;
        @Doc("Fields to order the results by.")
        public List<Sort> sort;
    }

    static class FrontierQuery extends BaseQuery {
        public String orderBy() {
            var columns = Map.of("id", "id",
                    "date", "date",
                    "url", "url",
                    "title", "title",
                    "visitTimeMs", "visit_time_ms",
                    "resources", "resources",
                    "size", "size");
            if (sort == null) return "";
            return "ORDER BY " + sort.stream().map(s -> s.sql(columns)).collect(Collectors.joining(", "));
        }
    }

    static class PagesQuery extends BaseQuery {
        public String orderBy() {
            var columns = Map.of("id", "id",
                    "date", "date",
                    "url", "url",
                    "title", "title",
                    "visitTimeMs", "visit_time_ms",
                    "resources", "resources",
                    "size", "size");
            if (sort == null) return "";
            return "ORDER BY " + sort.stream().map(s -> s.sql(columns)).collect(Collectors.joining(", "));
        }
    }

    static class ResourcesQuery extends BaseQuery {
        public String orderBy() {
            var columns = Map.ofEntries(Map.entry("id", "id"),
                    Map.entry("pageId", "page_id"),
                    Map.entry("date", "date"),
                    Map.entry("url", "url"),
                    Map.entry("filename", "filename"),
                    Map.entry("responseOffset", "response_offset"),
                    Map.entry("responseLength", "response_length"),
                    Map.entry("requestLength", "request_length"),
                    Map.entry("status", "status"),
                    Map.entry("redirect", "redirect"),
                    Map.entry("payloadType", "payload_type"),
                    Map.entry("payloadSize", "payload_size"),
                    Map.entry("payloadDigest", "payload_digest"),
                    Map.entry("fetchTimeMs", "fetch_time_ms"),
                    Map.entry("ipAddress", "ip_address"),
                    Map.entry("type", "type"),
                    Map.entry("protocol", "protocol"));
            if (sort == null) return "";
            return "ORDER BY " + sort.stream().map(s -> s.sql(columns)).collect(Collectors.joining(", "));
        }
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

    private void sendJson(HttpExchange exchange, Object object) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, 0);
        JSON.writeValue(exchange.getResponseBody(), object);
    }

    private void notFound(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.contains("..")) throw new IllegalArgumentException();

        var resource = getClass().getResource("/META-INF/resources" + path);
        if (resource != null) {
            var connection = resource.openConnection();
            try (var stream = connection.getInputStream()) {
                String type = path.endsWith(".mjs") ? "application/javascript" : URLConnection.guessContentTypeFromName(path);
                if (type != null) exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(200, connection.getContentLengthLong());
                stream.transferTo(exchange.getResponseBody());
                return;
            }
        }
        exchange.sendResponseHeaders(404, 0);
        exchange.getResponseBody().write("Not found".getBytes(UTF_8));
    }

}
