package org.netpreserve.warcaroo.config;

import org.netpreserve.warcaroo.util.Url;


import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonSubTypes({
        @JsonSubTypes.Type(value = MatchRule.Host.class),
        @JsonSubTypes.Type(value = MatchRule.Regex.class)
})
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
public sealed interface MatchRule permits MatchRule.Host, MatchRule.Regex, MatchRule.All {
    boolean matches(Url url);

    record All() implements MatchRule {

        @Override
        public boolean matches(Url url) {
            return true;
        }
    }

    record Host(String host) implements MatchRule {
        @Override
        public boolean matches(Url url) {
            return host.equalsIgnoreCase(url.host());
        }
    }

    record Regex(String regex) implements MatchRule {
        @Override
        public boolean matches(Url url) {
            return url.toString().matches(regex);
        }
    }
}
