package org.netpreserve.warcaroo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

public record Domain(long id, String rhost, long seeds, long pending, long failed,
                     long robotsExcluded, long pages, long resources, long size, long transferred, long storage) {
    @JsonProperty
    public String host() {
        if (rhost.contains(",")) {
            var segments = Arrays.asList(rhost.split(","));
            Collections.reverse(segments);
            return String.join(".", segments);
        } else {
            return rhost;
        }
    }
}
