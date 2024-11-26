package org.netpreserve.warcaroo.cdp.protocol;

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
import org.apache.commons.lang3.ArrayUtils;
import org.netpreserve.warcaroo.util.LogUtils;
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

public interface RPC {
    ObjectMapper JSON = new ObjectMapper(new JsonFactoryBuilder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(300 * 1024 * 1024).build())
            .build())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
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

        private static int findNullByte(byte[] buffer, int offset, int length) {
            for (int i = offset; i < length; i++) {
                if (buffer[i] == 0) return i;
            }
            return -1;
        }

        private void run() {
            ByteArrayOutputStream partialMessage = null;
            byte[] buffer = new byte[256 * 1024]; // 256 KiB
            try {
                while (true) {
                    int bytesRead = inputStream.read(buffer);
                    if (bytesRead < 0) {
                        log.info("Received end of stream");
                        return;
                    }
                    int startOfMessage = 0;
                    while (true) {
                        int endOfMessage = findNullByte(buffer, startOfMessage, bytesRead);
                        if (endOfMessage == -1) break;
                        if (partialMessage == null) {
                            handleMessage(buffer, startOfMessage, endOfMessage - startOfMessage);
                        } else {
                            partialMessage.write(buffer, startOfMessage, endOfMessage - startOfMessage);
                            byte[] message = partialMessage.toByteArray();
                            handleMessage(message, 0, message.length);
                            partialMessage = null;
                        }
                        startOfMessage = endOfMessage + 1;
                    }

                    // Copy leftover data if we have an incomplete message
                    if (startOfMessage < bytesRead) {
                        if (partialMessage == null) partialMessage = new ByteArrayOutputStream();
                        partialMessage.write(buffer, startOfMessage, bytesRead - startOfMessage);
                    }
                }
            } catch (IOException e) {
                log.error("Error reading CDPPipe", e);
            } finally {
                close();
            }
        }

        private void handleMessage(byte[] buffer, int offset, int len) throws IOException {
            if (log.isTraceEnabled()) {
                log.trace("<- {}", LogUtils.ellipses(new String(buffer, offset, len)));
            }
            var message = JSON.readValue(buffer, offset, len, ServerMessage.class);
            messageHandler.accept(message);
        }

        @Override
        public void send(Command message) throws IOException {
            writeLock.lock();
            try {
                if (log.isTraceEnabled()) {
                    log.trace("-> {}", LogUtils.ellipses(JSON.writeValueAsString(message)));
                }
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
