package org.netpreserve.warcbot.cdp;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class CDPSocket implements CDPConnection {
    private static final Logger log = LoggerFactory.getLogger(CDPSocket.class);
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private final WebSocket webSocket;
    private final Consumer<CDPClient.Result> messageHandler;

    public CDPSocket(URI devtoolsUrl, Consumer<CDPClient.Result> messageHandler) throws IOException {
        this.messageHandler = messageHandler;
        try {
            this.webSocket = httpClient.newWebSocketBuilder()
                    .buildAsync(devtoolsUrl, new Listener())
                    .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void send(CDPClient.Call message) throws JsonProcessingException {
        webSocket.sendText(CDPClient.json.writeValueAsString(message), true);
    }

    @Override
    public void close() {
        webSocket.sendClose(200, "");
        webSocket.abort();
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (!last) {
                buffer.append(data);
                webSocket.request(1);
                return null;
            }
            if (!buffer.isEmpty()) {
                buffer.append(data);
                data = buffer.toString();
                buffer.setLength(0);
            }
            try {
                var message = CDPClient.json.readValue(data.toString(), CDPClient.Result.class);
                messageHandler.accept(message);
            } catch (IOException e) {
                log.error("Failed to parse message", e);
            }
            webSocket.request(1);
            return null;
        }
    }
}
