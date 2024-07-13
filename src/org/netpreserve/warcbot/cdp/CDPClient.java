package org.netpreserve.warcbot.cdp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class CDPClient extends CDPBase implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CDPClient.class);
    static final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private final WebSocket webSocket;
    private final AtomicLong idSeq = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> calls = new ConcurrentHashMap<>();
    private final Map<String, Consumer<JsonNode>> listeners = new ConcurrentHashMap<>();
    private final Map<String, Consumer<JsonNode>> sessionListeners = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicLong threadId = new AtomicLong();
        @Override
        public Thread newThread(@NotNull Runnable r) {
            Thread thread = new Thread(r, "CDPClient-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    public CDPClient(URI devtoolsUrl) throws IOException {
        try {
            this.webSocket = httpClient.newWebSocketBuilder()
                    .buildAsync(devtoolsUrl, new WebSocketListener())
                    .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        try {
            webSocket.sendClose(200, "");
            webSocket.abort();
        } catch (Exception e) {
            log.warn("Failed to close web socket", e);
        }
        executor.shutdown();
    }

    private class WebSocketListener implements WebSocket.Listener {
        private StringBuilder buffer = new StringBuilder();

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
//            log.info("CDP RECV: {}", data);
            try {
                var message = json.readValue(data.toString(), Result.class);
                if (message.method != null) {
                    Consumer<JsonNode> handler;
                    if (message.sessionId == null) {
                        handler = listeners.get(message.method);
                    } else {
                        handler = sessionListeners.get(message.sessionId + " " + message.method);
                    }
                    if (handler != null) {
                        executor.submit(() -> { try {
                            handler.accept(message.params());
                        } catch (Exception e) {
                            log.error("{} handler threw", message.method, e);
                        }});
                    }
                } else {
                    var future = calls.get(message.id());
                    if (future == null) {
                        log.warn("Received response to unknown call id {}", message.id());
                    } else {
                        if (message.error == null) {
                            future.complete(message.result);
                        } else {
                            future.completeExceptionally(new CDPException(message.error.code(), message.error.message()));
                        }
                    }
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse response", e);
            }
            webSocket.request(1);
            return null;
        }
    }

    @Override
    public <T> void addListener(Class<T> eventClass, Consumer<T> callback) {
        listeners.put(eventName(eventClass), params -> {
            try {
                callback.accept(json.treeToValue(params, eventClass));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static String eventName(Class<?> eventClass) {
        String className = eventClass.getSimpleName();
        return  eventClass.getEnclosingClass().getSimpleName() + "."
                + className.substring(0, 1).toLowerCase(Locale.ROOT)
                + className.substring(1);
    }

    public <T> void addSessionListener(String sessionId, Class<T> eventClass, Consumer<T> callback) {
        sessionListeners.put(sessionId + " " + eventName(eventClass), params -> {
            try {
                callback.accept(json.treeToValue(params, eventClass));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    protected <T> T send(String method, Map<String, Object> params, Type returnType) {
        return send(method, params, returnType, null);
    }

    <T> T send(String method, Map<String, Object> params, Type returnType, String sessionId) {
        try {
            JsonNode result = send(new Call(idSeq.incrementAndGet(), method, params, sessionId))
                    .get(120, TimeUnit.SECONDS);
            return json.treeToValue(result, json.constructType(returnType));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof CDPException cdpException) {
                cdpException.actuallyFillInStackTrace();
                throw cdpException;
            } else if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            } else {
                throw new RuntimeException(e.getCause());
            }
        } catch (InterruptedException | TimeoutException | JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private CompletableFuture<JsonNode> send(Call call) {
        try {
            var future = new CompletableFuture<JsonNode>();
            calls.put(call.id(), future);
            String data = json.writeValueAsString(call);
            log.info("CDP SEND: {}", data);
            webSocket.sendText(data, true);
            return future;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    record Call(long id, String method, Map<String, Object> params, String sessionId) {
    }

    private record Result(long id, JsonNode result, Error error, String method, JsonNode params,
                          String sessionId) {
    }

    private record Error(int code, String message) {
    }
}
