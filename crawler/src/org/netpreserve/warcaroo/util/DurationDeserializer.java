package org.netpreserve.warcaroo.util;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;

public class DurationDeserializer extends JsonDeserializer<Duration> {
    @Override
    public Duration deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException, JacksonException {
        if (jsonParser.currentToken().isNumeric()) return Duration.ofMillis(jsonParser.getLongValue());
        return Duration.parse("PT" + jsonParser.getText().toUpperCase(Locale.ROOT));
    }
}
