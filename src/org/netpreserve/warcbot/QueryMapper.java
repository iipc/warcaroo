package org.netpreserve.warcbot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class QueryMapper {
    private static final ObjectMapper mapper = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS).build();

    public static <T> T parse(String queryString, Class<T> clazz) throws JsonProcessingException {
        return mapper.treeToValue(parse(queryString), clazz);
    }

    public static JsonNode parse(String queryString) {
        ObjectNode result = mapper.createObjectNode();
        if (queryString == null || queryString.isEmpty()) {
            return result;
        }

        for (String pair : queryString.split("&")) {
            String[] keyValue = pair.split("=", 2);
            String key = keyValue[0];
            String value = keyValue.length > 1 ? keyValue[1] : "";
            String[] keys = key.split("]\\[|\\[|]", -1);
            int keysLen = keys.length;
            if (keysLen > 1) {
                keysLen--; // ignore trailing "" after ]
            }

            JsonNode currentNode = result;
            for (int i = 0; i < keysLen; i++) {
                String currentKey = keys[i];
                JsonNode nextNode;
                if (currentKey.isEmpty()) {
                    nextNode = null;
                } else if (currentKey.matches("\\d+")) {
                    nextNode = currentNode.get(Integer.parseInt(currentKey));
                } else {
                    nextNode = currentNode.get(currentKey);
                }
                if (nextNode == null) {
                    if (i + 1 >= keysLen) {
                        nextNode = mapper.getNodeFactory().textNode(value);
                    } else if (keys[i + 1].matches("\\d+|")) {
                        nextNode = mapper.createArrayNode();
                    } else {
                        nextNode = mapper.createObjectNode();
                    }
                    set(currentNode, currentKey, nextNode);
                }
                currentNode = nextNode;
            }
        }

        return result;
    }

    private static void set(JsonNode node, String key, JsonNode value) {
        switch (node) {
            case ObjectNode objectNode -> objectNode.set(key, value);
            case ArrayNode arrayNode -> {
                if (key.isEmpty()) {
                    arrayNode.add(value);
                } else {
                    int index = Integer.parseInt(key);
                    for (int i = arrayNode.size(); i <= index; i++) {
                        arrayNode.addNull();
                    }
                    arrayNode.set(index, value);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported node type: " + node.getClass().getName());
        }
    }
}
