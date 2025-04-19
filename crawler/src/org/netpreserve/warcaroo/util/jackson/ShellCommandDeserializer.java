package org.netpreserve.warcaroo.util.jackson;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON deserializer which takes a shell command like "ssh user@host" and returns a list of ["ssh", "user@host"].
 */
public class ShellCommandDeserializer extends JsonDeserializer<List<String>> {
    @Override
    public List<String> deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException, JacksonException {
        JsonNode node = jsonParser.getCodec().readTree(jsonParser);
        if (node.isTextual()) {
            return parseShellCommand(node.asText());
        } else if (node.isArray()) {
            List<String> tokens = new ArrayList<>();
            for (JsonNode element : node) {
                tokens.add(element.asText());
            }
            return tokens;
        } else {
            throw new JsonMappingException(jsonParser, "Invalid shell command: " + node.asText(null) + " (expected string or array of strings)");
        }
    }

    private List<String> parseShellCommand(String commandLine) throws IOException {
        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        char activeQuote = 0;
        boolean tokenStarted = false;

        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);
            if (activeQuote != 0) {
                if (c == activeQuote) {
                    activeQuote = 0; // Closing quote found, exit quote mode
                } else {
                    currentToken.append(c);
                }
            } else { // Not inside quotes
                if (c == '\'' || c == '\"') {
                    activeQuote = c;
                    tokenStarted = true;
                } else if (Character.isWhitespace(c)) {
                    if (tokenStarted) {
                        tokens.add(currentToken.toString());
                        currentToken.setLength(0);
                        tokenStarted = false;
                    }
                } else {
                    currentToken.append(c);
                    tokenStarted = true;
                }
            }
        }
        if (activeQuote != 0) throw new IOException("Unterminated quote in: \"" + commandLine + "\"");
        if (tokenStarted) tokens.add(currentToken.toString());
        return tokens;

    }
}
