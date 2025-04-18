package org.netpreserve.warcaroo.util;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.util.Locale;
import java.util.regex.Pattern;

import java.io.IOException;

public class ByteSizeDeserializer extends JsonDeserializer<Long> {
    private static final Pattern SIZE_PATTERN =
            Pattern.compile("(?i)\\s*(\\d+(?:\\.\\d+)?)\\s*([KMGT]?)(B?)\\s*");

    @Override
    public Long deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException, JacksonException {
        if (jsonParser.currentToken().isNumeric()) return jsonParser.getLongValue();
        var matcher = SIZE_PATTERN.matcher(jsonParser.getText());
        if (!matcher.matches()) {
            throw new IOException("Invalid byte size format: " + jsonParser.getText());
        }
        double value = Double.parseDouble(matcher.group(1));
        long multiplier = switch (matcher.group(2).toUpperCase(Locale.ROOT)) {
            case "K" -> 1024L;
            case "M" -> 1024L * 1024;
            case "G" -> 1024L * 1024 * 1024;
            case "T" -> 1024L * 1024 * 1024 * 1024;
            default -> 1L;
        };
        return (long) (value * multiplier);
    }
}
