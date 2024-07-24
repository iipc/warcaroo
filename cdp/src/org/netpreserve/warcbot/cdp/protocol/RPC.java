package org.netpreserve.warcbot.cdp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public interface RPC {
    ObjectMapper JSON = new ObjectMapper(new JsonFactoryBuilder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(300 * 1024 * 1024).build())
            .build())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    void send(Command message) throws IOException;

    void close();

    record Command(long id, String method, Map<String, Object> params, String sessionId) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({@JsonSubTypes.Type(Event.class), @JsonSubTypes.Type(Response.class)})
    interface ServerMessage {
        String sessionId();
    }

    record Event(String method, ObjectNode params, String sessionId) implements ServerMessage {
    }

    record Response(long id, ObjectNode result, Error error, String sessionId) implements ServerMessage {
    }

    record Error(int code, String message) {
    }

    class Socket implements RPC {
        private static final Logger log = LoggerFactory.getLogger(Socket.class);
        private static final HttpClient httpClient = HttpClient.newHttpClient();
        private final WebSocket webSocket;
        private final Consumer<ServerMessage> messageHandler;

        public Socket(URI devtoolsUrl, Consumer<ServerMessage> messageHandler) throws IOException {
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
        public void send(Command message) throws JsonProcessingException {
            webSocket.sendText(JSON.writeValueAsString(message), true);
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
                    var message = JSON.readValue(data.toString(), ServerMessage.class);
                    messageHandler.accept(message);
                } catch (IOException e) {
                    log.error("Failed to parse message", e);
                }
                webSocket.request(1);
                return null;
            }
        }
    }

    class Pipe implements RPC {
        private static final Logger log = LoggerFactory.getLogger(Pipe.class);
        private static final Pattern BIG_STRING = Pattern.compile("\"([^\"]{20})[^\"]{40,}([^\"]{20})", Pattern.DOTALL | Pattern.MULTILINE);
        private final InputStream inputStream;
        private final OutputStream outputStream;
        private final Consumer<ServerMessage> messageHandler;
        private final Lock writeLock = new ReentrantLock();

        public Pipe(InputStream inputStream, OutputStream outputStream, Consumer<ServerMessage> messageHandler) {
            this.inputStream = inputStream;
            this.outputStream = outputStream;
            this.messageHandler = messageHandler;
            var thread = new Thread(this::run, "CDP.Pipe");
            thread.setDaemon(true);
            thread.start();
        }

        private void run() {
            try {
                while (true) {
                    var buffer = new ByteArrayOutputStream();
                    while (true) {
                        int b = inputStream.read();
                        if (b < 0) return;
                        if (b == 0) break;
                        buffer.write(b);
                    }
                    if (log.isTraceEnabled()) {
                        log.trace("<- {}", BIG_STRING.matcher(buffer.toString()).replaceAll("\"$1...$2\""));
                    }
                    var message = JSON.readValue(buffer.toByteArray(), ServerMessage.class);
                    messageHandler.accept(message);
                }
            } catch (IOException e) {
                log.error("Error reading CDPPipe", e);
            } finally {
                close();
            }
        }

        @Override
        public void send(Command message) throws IOException {
            writeLock.lock();
            try {
                if (log.isTraceEnabled()) log.trace("-> {}", JSON.writeValueAsString(message));
                JSON.writeValue(outputStream, message);
                outputStream.write(0);
                outputStream.flush();
            } finally {
                writeLock.unlock();
            }
        }

        @Override
        public void close() {
            try {
                outputStream.close();
            } catch (IOException e) {
                // ignore
            }
            try {
                inputStream.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
