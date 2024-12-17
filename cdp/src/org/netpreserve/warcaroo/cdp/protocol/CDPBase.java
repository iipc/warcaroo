package org.netpreserve.warcaroo.cdp.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.core.util.Separators.Spacing;
import com.fasterxml.jackson.databind.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static org.netpreserve.warcaroo.util.LogUtils.ellipses;

public abstract class CDPBase {
    private static final Logger log = LoggerFactory.getLogger(CDPBase.class);
    private static final ObjectWriter logJson = RPC.JSON.copy()
            .writer().without(JsonWriteFeature.QUOTE_FIELD_NAMES)
            .with(new DefaultPrettyPrinter()
                    .withArrayIndenter(null)
                    .withObjectIndenter(null)
                    .withSeparators(new Separators()
                            .withObjectEntrySpacing(Spacing.AFTER)
                            .withObjectFieldValueSpacing(Spacing.AFTER)));
    private final Map<Long, CompletableFuture<JsonNode>> commands = new ConcurrentHashMap<>();
    private final Map<String, Consumer<JsonNode>> listeners = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private Thread executorThread;

    protected CDPBase() {
        String parentThreadName = Thread.currentThread().getName();
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, parentThreadName + "-CDP");
            thread.setDaemon(true);
            executorThread = thread;
            return thread;
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> T domain(Class<T> domainInterface) {
        return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{domainInterface},
                (proxy, method, args) -> {
                    var methodParameters = method.getParameters();
                    if (method.getName().equals("toString")) {
                        return domainInterface.getSimpleName() + "@" + System.identityHashCode(proxy);
                    }
                    if (method.getName().startsWith("on") && methodParameters.length == 1) {
                        var type = ((ParameterizedType)method.getGenericParameterTypes()[0]);
                        var eventClass = (Class<?>)type.getActualTypeArguments()[0];
                        addListener(eventClass, (Consumer)args[0]);
                        return null;
                    }
                    var params = new HashMap<String, Object>(methodParameters.length);
                    for (int i = 0; i < methodParameters.length; i++) {
                        if (args[i] != null) params.put(methodParameters[i].getName(), args[i]);
                    }
                    Unwrap unwrap = method.getAnnotation(Unwrap.class);
                    return sendCommand(domainInterface.getSimpleName() + "." + method.getName(), params,
                            method.getGenericReturnType(), unwrap);
                });
    }

    protected void handleMessage(RPC.ServerMessage message) {
        try {
            executor.submit(() -> {
                switch (message) {
                    case RPC.Event event -> handleEvent(event);
                    case RPC.Response response -> handleResponse(response);
                    case null, default -> log.error("Unknown message type: {}", message);
                }
            });
        } catch (RejectedExecutionException e) {
            log.debug("Caught rejected execution exception, session is probably closing", e);
        }
    }

    private void handleResponse(RPC.Response response) {
        var future = commands.remove(response.id());
        if (future == null) {
            log.warn("Received response to unknown call id {}", response.id());
        } else {
            if (response.error() == null) {
                future.complete(response.result());
            } else {
                future.completeExceptionally(new CDPException(response.error().code(), response.error().message()));
            }
        }
    }

    private void handleEvent(RPC.Event event) {
        if (log.isTraceEnabled()) {
            try {
                log.trace("{}{}", event.method(), ellipses(logJson.writeValueAsString(event.params())));
            } catch (JsonProcessingException ignored) {}
        }
        Consumer<JsonNode> handler = listeners.get(event.method());
        if (handler != null) {
            try {
                handler.accept(event.params());
            } catch (Exception e) {
                log.error("{} handler threw", event.method(), e);
            }
        }
    }

    private static String eventName(Class<?> eventClass) {
        String className = eventClass.getSimpleName();
        return  eventClass.getEnclosingClass().getSimpleName() + "."
                + className.substring(0, 1).toLowerCase(Locale.ROOT)
                + className.substring(1);
    }

    public <T> void addListener(Class<T> eventClass, Consumer<T> callback) {
        listeners.put(eventName(eventClass), params -> {
            try {
                callback.accept(RPC.JSON.treeToValue(params, eventClass));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });
    }

    Object sendCommand(String method, Map<String, Object> params, Type returnType, Unwrap unwrap) {
        if (Thread.currentThread() == executorThread
            && !(returnType instanceof ParameterizedType parameterizedType &&
                 CompletionStage.class.isAssignableFrom((Class<?>) parameterizedType.getRawType()))) {
            throw new IllegalStateException("Sending command on the event handler thread would deadlock");
        }

        if (method.endsWith("Async")) {
            method = method.substring(0, method.length() - "Async".length());
        }

        long commandId = nextCommandId();

        if (log.isTraceEnabled()) {
            try {
                log.trace("[{}] {}{}", commandId, method, ellipses(logJson.writeValueAsString(params)));
            } catch (JsonProcessingException ignored) {}
        }


        boolean leaveCommandInMap = false;
        try {
            var future = new CompletableFuture<JsonNode>();

            if (log.isTraceEnabled()) {
                String methodName = method;
                future.whenComplete((result, ex) -> {
                    try {
                        if (ex == null) {
                            log.trace("[{}] {} [{}]", commandId, ellipses(logJson.writeValueAsString(result)), methodName);
                        } else {
                            log.trace("[{}] {}", commandId, ex.getMessage());
                        }
                    } catch (JsonProcessingException ignored) {}
                });
            }

            commands.put(commandId, future);
            sendCommandMessage(commandId, method, params);

            Type valueType;
            boolean returnsCompletionStage;
            if (returnType instanceof ParameterizedType parameterizedType &&
                CompletionStage.class.isAssignableFrom((Class<?>)parameterizedType.getRawType())) {
                valueType = parameterizedType.getActualTypeArguments()[0];
                returnsCompletionStage = true;
            } else {
                valueType = returnType;
                returnsCompletionStage = false;
            }

            CompletableFuture<?> mappedFuture = future.thenApply(result -> {
                ObjectReader reader;
                if (unwrap != null) {
                    String rootName = unwrap.value().isEmpty() ?
                            lowercaseFirstLetter(((Class<?>)valueType).getSimpleName()) : unwrap.value();
                    reader = RPC.JSON.reader(DeserializationFeature.UNWRAP_ROOT_VALUE)
                            .withRootName(rootName);
                } else {
                    reader = RPC.JSON.reader();
                }
                try {
                    return reader.treeToValue(result, RPC.JSON.constructType(valueType));
                } catch (JsonProcessingException e) {
                    throw new UncheckedIOException(e);
                }
            });
            if (returnsCompletionStage) {
                leaveCommandInMap = true;
                return mappedFuture;
            } else {
                return mappedFuture.get(120, TimeUnit.SECONDS);
            }
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
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            throw new CDPTimeoutException("Interrupted");
        } finally {
            if (!leaveCommandInMap) commands.remove(commandId);
        }
    }

    private static String lowercaseFirstLetter(String s) {
        return s.substring(0, 1).toLowerCase(Locale.ROOT) + s.substring(1);
    }

    protected abstract void sendCommandMessage(long commandId, String method, Map<String, Object> params) throws IOException;

    protected abstract long nextCommandId();

    protected void close() {
        executor.shutdown();
    }

    protected void handleRpcClose() {
        commands.values().forEach(command ->
                command.completeExceptionally(new CDPClosedException()));
    }
}
