package org.netpreserve.warcaroo.config;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum ScopeType {
    PREFIX, PAGE, HOST, DOMAIN;

    @JsonCreator
    public static ScopeType fromString(String value) {
        return value == null ? null : valueOf(value.toUpperCase());
    }
}
