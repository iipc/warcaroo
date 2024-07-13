package org.netpreserve.warcbot.cdp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public interface Runtime {
    Evaluate evaluate(String expression, int timeout, boolean returnByValue, boolean awaitPromise);

    record Evaluate(RemoteObject result, ExceptionDetails exceptionDetails) {
    }

    record RemoteObject(String type, JsonNode value) {
        public Object toJavaObject() {
            return switch (type) {
                case "string" -> value.asText();
                case "object" -> {
                    try {
                        if (value.isArray()) {
                            yield CDPClient.json.treeToValue(value, List.class);
                        } else {
                            yield CDPClient.json.treeToValue(value, Map.class);
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
}
