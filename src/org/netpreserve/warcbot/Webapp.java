package org.netpreserve.warcbot;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static java.net.URLDecoder.decode;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toMap;

public class Webapp implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(Webapp.class);
    private static final ObjectMapper JSON = new ObjectMapper()
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
    private final WarcBot warcbot;

    public Webapp(WarcBot warcbot) {
        this.warcbot = warcbot;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            switch (exchange.getRequestURI().getPath()) {
                case "/" -> home(exchange);
                case "/events" -> events(exchange);
                case "/start" -> warcbot.start();
                case "/api/frontier" -> frontier(exchange);
                case "/api/pages" -> pages(exchange);
                case "/api/resources" -> resources(exchange);
                default -> notFound(exchange);
            }
            try {
                exchange.sendResponseHeaders(200, 0);
            } catch (IOException e) {
                // ignore
            }
        } catch (IllegalArgumentException e) {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(400, 0);
            exchange.getResponseBody().write((e.getMessage() + "\n").getBytes(UTF_8));
        } catch (Exception e) {
            log.error("Error handling request " + exchange.getRequestURI(), e);
        } finally {
            exchange.close();
        }
    }

    record Sort(String field, Direction dir) {
        String sql(Map<String, String> columns) {
            var column = columns.get(field);
            if (column == null) throw new IllegalArgumentException("No column for field: " + field);
            return dir == Direction.DESC ? column + " DESC" : column;
        }

        enum Direction {
            ASC, DESC;
        }
    }

    abstract static class BaseQuery {
        public long page = 1;
        public int size = 10;
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

    private void frontier(HttpExchange exchange) throws IOException {
        var query = QueryMapper.parse(exchange.getRequestURI().getQuery(), FrontierQuery.class);
        long count = warcbot.db.storage().countPages();
        sendJson(exchange, new PageData(count / query.size + 1, count,
                warcbot.db.frontier().queryFrontier(query.orderBy(), query.size, (query.page - 1) * query.size)));
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

    private void pages(HttpExchange exchange) throws IOException {
        var query = QueryMapper.parse(exchange.getRequestURI().getQuery(), PagesQuery.class);
        long count = warcbot.db.storage().countPages();
        sendJson(exchange, new PageData(count / query.size + 1, count,
                warcbot.db.storage().queryPages(query.orderBy(), query.size, (query.page - 1) * query.size)));
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

    private void resources(HttpExchange exchange) throws IOException {
        var query = QueryMapper.parse(exchange.getRequestURI().getQuery(), ResourcesQuery.class);
        long count = warcbot.db.storage().countResources();
        sendJson(exchange, new PageData(count / query.size + 1, count,
                warcbot.db.storage().queryResources(query.orderBy(), query.size, (query.page - 1) * query.size)));
    }

    record PageData (long last_page, long last_row, Object data) {

    }

    private void sendJson(HttpExchange exchange, Object object) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, 0);
        JSON.writeValue(exchange.getResponseBody(), object);
    }

    private void home(HttpExchange exchange) throws IOException {
        var connection = Objects.requireNonNull(getClass().getResource("assets/main.html"), "Resource main.html")
                .openConnection();
        try (var stream = connection.getInputStream()){
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, connection.getContentLengthLong());
            stream.transferTo(exchange.getResponseBody());
        }
    }

    private void notFound(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.contains("..")) throw new IllegalArgumentException();

        var resource = getClass().getResource("/META-INF/resources" + path);
        if (resource != null) {
            var connection = resource.openConnection();
            try (var stream = connection.getInputStream()) {
                String type = path.endsWith(".mjs") ? "application/javascript" : URLConnection.guessContentTypeFromName(path);
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(200, connection.getContentLengthLong());
                stream.transferTo(exchange.getResponseBody());
                return;
            }
        }
        exchange.sendResponseHeaders(404, 0);
        exchange.getResponseBody().write("Not found".getBytes(UTF_8));
    }

    private void events(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        var writer = new OutputStreamWriter(exchange.getResponseBody(), UTF_8);
        double smoothingFactor = 0.05;
        double averageDownloadSpeed = 0;
        long lastTime = System.nanoTime();
        long lastBytes = warcbot.tracker.downloadedBytes();
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            long time = System.nanoTime();
            long bytes = warcbot.tracker.downloadedBytes();

            long elapsed = time - lastTime;
            long bytesDelta = bytes - lastBytes;

            double downloadSpeed = bytesDelta / (elapsed / 1000000000.0);
            averageDownloadSpeed = smoothingFactor * downloadSpeed + (1 - smoothingFactor) * averageDownloadSpeed;

            writer.write("event: progress\ndata: ");
            JSON.writeValue(writer, Map.of("speed", averageDownloadSpeed));
            writer.write("\n\n");
            writer.flush();

            lastBytes = bytes;
            lastTime = time;
        }
    }
}
