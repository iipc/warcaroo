package org.netpreserve.warcbot.cdp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class CDPClient extends CDPBase implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CDPClient.class);
    static final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
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
    private final CDPConnection connection;

    public CDPClient(URI devtoolsUrl) throws IOException {
        this.connection = new CDPSocket(devtoolsUrl, this::handleMessage);
    }

    public CDPClient(InputStream inputStream, OutputStream outputStream) {
        this.connection = new CDPPipe(inputStream, outputStream, this::handleMessage);
    }

    @Override
    public void close() {
        connection.close();
        executor.shutdown();
    }

    private void handleMessage(Result message) {
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
            var future = calls.remove(message.id());
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
        long callId = idSeq.incrementAndGet();
        try {
            JsonNode result = sendAsync(new Call(callId, method, params, sessionId))
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
        } catch (TimeoutException e) {
            throw new CDPTimeoutException("Timed out");
        } catch (InterruptedException | JsonProcessingException e) {
            throw new RuntimeException(e);
        } finally {
            calls.remove(callId);
        }
    }

    private CompletableFuture<JsonNode> sendAsync(Call call) {
        try {
            var future = new CompletableFuture<JsonNode>();
            calls.put(call.id(), future);
            connection.send(call);
            return future;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record Call(long id, String method, Map<String, Object> params, String sessionId) {
    }

    record Result(long id, JsonNode result, Error error, String method, JsonNode params,
                          String sessionId) {
    }

    private record Error(int code, String message) {
    }
}
