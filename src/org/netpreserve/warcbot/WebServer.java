package org.netpreserve.warcbot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public class WebServer implements HttpHandler {
    private final Warcbot warcbot;

    public WebServer(Warcbot warcbot) {
        this.warcbot = warcbot;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
    }
}
