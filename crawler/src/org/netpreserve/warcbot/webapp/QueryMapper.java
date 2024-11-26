package org.netpreserve.warcaroo.webapp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLDecoder;

import static java.nio.charset.StandardCharsets.UTF_8;

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

        for (String param : queryString.split("&")) {
            String[] pair = param.split("=", 2);
            String name = pair[0];
            String value = pair.length > 1 ? pair[1] : "";
            String[] keys = name.split("]\\[|\\[|]", -1);
            int keysLen = keys.length;
            if (keysLen > 1) {
                keysLen--; // ignore trailing "" after ]
            }

            JsonNode node = result;
            for (int i = 0; i < keysLen; i++) {
                String key = URLDecoder.decode(keys[i], UTF_8);
                JsonNode child;
                if (key.isEmpty()) {
                    child = null;
                } else if (key.matches("\\d+")) {
                    child = node.get(Integer.parseInt(key));
                } else {
                    child = node.get(key);
                }
                if (child == null) {
                    if (i + 1 >= keysLen) {
                        child = mapper.getNodeFactory().textNode(URLDecoder.decode(value, UTF_8));
                    } else if (keys[i + 1].matches("\\d+|")) {
                        child = mapper.createArrayNode();
                    } else {
                        child = mapper.createObjectNode();
                    }
                    set(node, key, child);
                }
                node = child;
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
