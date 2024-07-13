package org.netpreserve.warcbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;

public class CrawlServer implements HttpHandler {
    private final Crawl crawl;
    private final ObjectMapper json = new ObjectMapper();

    public CrawlServer(Crawl crawl) {
        this.crawl = crawl;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            switch (exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()) {
                case "GET /config" -> send(exchange, crawl.getConfig());
                case "PUT /config" -> crawl.setConfig(json.readValue(exchange.getRequestBody(), Config.class));
                case "POST /pause" -> crawl.pause();
                case "POST /unpause" -> crawl.unpause();
                default -> {
                    exchange.sendResponseHeaders(404, 0);
                    exchange.getResponseBody().write("Not found\n".getBytes(UTF_8));
                }
            }
            exchange.sendResponseHeaders(200, -1);
        } catch (Exception e) {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(500, 0);
            e.printStackTrace(new PrintStream(exchange.getResponseBody()));
        } finally {
            exchange.close();
        }
    }

    private void send(HttpExchange exchange, Object obj) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, 0);
        json.writeValue(exchange.getResponseBody(), obj);
    }

    public static void main(String[] args) throws Exception {
        try (WarcBot crawl = new WarcBot(Path.of("data"), new Config())) {
            var httpServer = HttpServer.create(new InetSocketAddress(8080), 0, "/",
                    new CrawlServer(crawl));
            httpServer.start();
        }
    }
}
