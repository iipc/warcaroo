package org.netpreserve.warcbot.cdp.domains;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Base64;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;

public interface IO {
    Read read(StreamHandle handle, Integer size);

    void close(StreamHandle handle);

    record Read(byte[] data, boolean eof) {
        @JsonCreator
        public Read(@JsonProperty("base64Encoded") boolean base64Encoded, @JsonProperty("data") String data,
                    @JsonProperty("eof") boolean eof) {
            this(base64Encoded ? Base64.getDecoder().decode(data) : data.getBytes(UTF_8), eof);
        }
    }

    record StreamHandle(@JsonValue String value) {
        @JsonCreator
        public StreamHandle {
            Objects.requireNonNull(value);
        }
    }
}
