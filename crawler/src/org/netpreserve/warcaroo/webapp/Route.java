package org.netpreserve.warcaroo.webapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.nio.charset.StandardCharsets.UTF_8;

public class Route implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(Route.class);
    private final Object controller;
    final Map<String, Method> methods = new HashMap<>();

    public Route(Object controller) {
        this.controller = controller;
    }

    static Map<String, Route> buildMap(Object controller) {
        var routes = new HashMap<String, Route>();
        for (var method : controller.getClass().getDeclaredMethods()) {
            for (var annotation : method.getDeclaredAnnotations()) {
                String path = switch (annotation) {
                    case GET get -> get.value();
                    case POST post -> post.value();
                    case PUT put -> put.value();
                    default -> null;
                };
                if (path == null) continue;
                Route route = routes.computeIfAbsent(path, k -> new Route(controller));
                route.methods.put(annotation.annotationType().getSimpleName(), method);
            }
        }
        System.out.println(routes);
        return routes;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Method method = methods.get(exchange.getRequestMethod());
        if (method == null) {
            exchange.getResponseHeaders().add("Allow", String.join(", ", methods.keySet()));
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        var args = new ArrayList<>();
        for (var type : method.getParameterTypes()) {
            if (type == HttpExchange.class) {
                args.add(exchange);
            } else {
                try {
                    if (exchange.getRequestMethod().equals("PUT")) {
                        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                        if (contentType == null || contentType.replaceFirst("\\s*;.*","")
                                .equals("application/json")) {
                            args.add(Webapp.JSON.reader().readValue(exchange.getRequestBody(), type));
                        } else {
                            exchange.getResponseHeaders().set("Accept", "application/json");
                            exchange.sendResponseHeaders(415, -1);
                            return;
                        }
                    } else {
                        args.add(QueryMapper.parse(exchange.getRequestURI().getQuery(), type));
                    }
                } catch (UnrecognizedPropertyException e) {
                    exchange.sendResponseHeaders(400, 0);
                    exchange.getResponseBody().write(("Unknown parameter: " + e.getPropertyName() + "\n" +
                                                      "Known parameters: " + e.getKnownPropertyIds()).getBytes(UTF_8));
                    return;
                }
            }
        }
        Object result;
        try {
            result = method.invoke(controller, args.toArray());
        } catch (InvocationTargetException e) {
            var cause = e.getCause();
            if (cause instanceof IOException && "Broken pipe".equals(cause.getMessage())) {
                return; // client closed the connection
            }
            log.error("Error invoking " + method, e.getCause());
            sendError(exchange, cause);
            return;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        if (result != null) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try (var bodyStream = encodeResponse(exchange, 200, 0)) {
                Webapp.JSON.writeValue(bodyStream, result);
            }
        } else {
            try {
                exchange.sendResponseHeaders(200, -1);
            } catch (IOException e) {
                if (!e.getMessage().contains("headers already sent")) {
                    throw e;
                }
            }
        }
    }

    private static void sendError(HttpExchange exchange, Throwable cause) throws IOException {
        int status = 500;
        var errorAnnotation = cause.getClass().getAnnotation(HttpError.class);
        if (errorAnnotation != null) {
            status = errorAnnotation.value();
        }
        StringWriter writer = new StringWriter();
        cause.printStackTrace(new PrintWriter(writer));
        var body = writer.toString()
                .replaceFirst("(?s)\n\tat org\\.netpreserve\\.warcaroo\\.webapp\\.Webapp\\.handle.*", "\n")
                .replaceAll("\n\tat java\\.base/jdk\\.internal\\.reflect\\..*", "")
                .replaceAll("\n\tat java\\.base/java\\.lang\\.reflect\\.Method\\.invoke.*", "")
                .getBytes(UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    public static OutputStream encodeResponse(HttpExchange exchange, int status, long length) throws IOException {
        String contentType = exchange.getResponseHeaders().getFirst("Content-Type");
        if (length != -1 && getAcceptedEncodings(exchange).contains("gzip")
            && contentType != null && (!contentType.startsWith("image/") || contentType.endsWith("+xml"))) {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(status, 0);
            return new GZIPOutputStream(exchange.getResponseBody());
        } else {
            exchange.sendResponseHeaders(status, length);
            return exchange.getResponseBody();
        }
    }

    private static Set<String> getAcceptedEncodings(HttpExchange exchange) {
        var header = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        if (header == null) return Set.of();
        return Set.of(header.split("\\s*,\\s*"));
    }

    @Target(METHOD)
    @Retention(RUNTIME)
    public @interface GET {
        String value();
    }

    @Target(METHOD)
    @Retention(RUNTIME)
    public @interface POST {
        String value();
    }

    @Target(METHOD)
    @Retention(RUNTIME)
    public @interface PUT {
        String value();
    }

    @Target(TYPE)
    @Retention(RUNTIME)
    public @interface HttpError {
        int value();
    }
}
