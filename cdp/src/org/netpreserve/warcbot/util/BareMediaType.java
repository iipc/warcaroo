package org.netpreserve.warcaroo.util;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public record BareMediaType(@JsonValue String value) {
    @JsonCreator
    public BareMediaType {
        assert value.indexOf(';') == -1;
    }

    public static BareMediaType of(String value) {
        if (value == null) return null;
        var semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon);
        }
        return new BareMediaType(value.strip());
    }
}
