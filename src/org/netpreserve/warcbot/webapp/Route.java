package org.netpreserve.warcbot.webapp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

public class Route implements HttpHandler {
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
        var type = method.getParameterTypes()[0];
        Object arg;
        if (type == HttpExchange.class) {
            arg = exchange;
        } else {
            arg = QueryMapper.parse(exchange.getRequestURI().getQuery(), type);
        }
        Object result;
        try {
            result = method.invoke(controller, arg);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, 0);
        Webapp.JSON.writeValue(exchange.getResponseBody(), result);
    }

    @Retention(RUNTIME)
    public @interface GET {
        String value();
    }

    @Retention(RUNTIME)
    public @interface POST {
        String value();
    }
}
