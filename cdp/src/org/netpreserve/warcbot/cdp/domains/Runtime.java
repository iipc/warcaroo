package org.netpreserve.warcbot.cdp.domains;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.netpreserve.warcbot.cdp.protocol.RPC;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface Runtime {
    Evaluate evaluate(String expression, int timeout, boolean returnByValue, boolean awaitPromise,
                      ExecutionContextUniqueId uniqueContextId);

    void onConsoleAPICalled(Consumer<ConsoleAPICalled> event);

    void onExecutionContextCreated(Consumer<ExecutionContextCreated> handler);

    void enable();

    record ConsoleAPICalled(String type, JsonNode args) {}

    record ExecutionContextCreated(ExecutionContextDescription context) {
    }

    record ExecutionContextDescription(ExecutionContextId id, String origin, String name, ExecutionContextUniqueId uniqueId,
                                       AuxData auxData) {
    }

    record AuxData(Page.FrameId frameId) {
    }

    record Evaluate(RemoteObject result, ExceptionDetails exceptionDetails) {
    }

    record RemoteObject(String type, JsonNode value) {
        public Object toJavaObject() {
            return switch (type) {
                case "string" -> value.asText();
                case "object" -> {
                    try {
                        if (value.isArray()) {
                            yield RPC.JSON.treeToValue(value, List.class);
                        } else {
                            yield RPC.JSON.treeToValue(value, Map.class);
                        }
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                }
                case "undefined" -> null;
                default -> throw new IllegalStateException("Don't know how to convert to Java object: " + type);
            };
        }
    }

    record ExceptionDetails(int exceptionId, String text, int lineNumber, int columnNumber) {
    }

    record ExecutionContextId(@JsonValue int value) {
        @JsonCreator
        public ExecutionContextId {}
    }

    record ExecutionContextUniqueId(@JsonValue String value) {
        @JsonCreator
        public ExecutionContextUniqueId {}
    }

}
