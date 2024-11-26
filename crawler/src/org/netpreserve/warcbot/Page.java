package org.netpreserve.warcaroo;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.jdbi.v3.core.mapper.Nested;
import org.netpreserve.warcaroo.util.Url;

import java.time.Instant;

public record Page(
        long id,
        Url url,
        Instant date,
        String title,
        long visitTimeMs,
        long hostId,
        long domainId,
        Long mainResourceId,
        long resources,
        long size) {

    public record Ext(@JsonUnwrapped @Nested Page page, Integer status) {
    }
}
