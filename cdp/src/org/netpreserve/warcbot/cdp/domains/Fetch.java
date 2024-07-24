package org.netpreserve.warcbot.cdp.domains;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public interface Fetch {
    void enable(List<RequestPattern> patterns);
    void disable();

    void continueRequest(RequestId requestId, boolean interceptResponse);
    void continueResponse(RequestId requestId);
    Network.ResponseBody getResponseBody(RequestId requestId);

    void onRequestPaused(Consumer<RequestPaused> handler);

    void fulfillRequest(RequestId requestId, int responseCode, byte[] binaryResponseHeaders,
                        byte[] body, String reasonPhrase);

    void failRequest(RequestId requestId, String errorReason);

    record RequestId(@JsonValue String value) {
        @JsonCreator
        public RequestId {
            Objects.requireNonNull(value);
        }
    }

    record RequestPattern(
            String urlPattern,
            String resourceType,
            String requestStage
    ){
    }

    record RequestPaused(
            RequestId requestId,
            Network.Request request,
            String frameId,
            String resourceType,
            String responseErrorReason,
            Integer responseStatusCode,
            String responseStatusText,
            List<HeaderEntry> responseHeaders,
            String networkId,
            RequestId redirectedRequestId
    ) {
        public boolean isResponseStage() {
            return responseStatusCode != null || responseErrorReason != null;
        }
    }

    record HeaderEntry(String name, String value) {
    }
}
