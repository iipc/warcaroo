package org.netpreserve.warcaroo.cdp.domains;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.netpreserve.warcaroo.cdp.protocol.Unwrap;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public interface Fetch {
    void enable(List<RequestPattern> patterns);
    void disable();

    void continueRequest(RequestId requestId, boolean interceptResponse);
    CompletionStage<Void> continueRequestAsync(RequestId requestId, boolean interceptResponse);
    CompletionStage<Void> continueResponseAsync(RequestId requestId);
    Network.ResponseBody getResponseBody(RequestId requestId);
    @Unwrap("stream")
    IO.StreamHandle takeResponseBodyAsStream(RequestId requestId);

    void onRequestPaused(Consumer<RequestPaused> handler);

    void fulfillRequest(RequestId requestId, int responseCode, byte[] binaryResponseHeaders,
                        byte[] body, String reasonPhrase);
    CompletionStage<Void> fulfillRequestAsync(RequestId requestId, int responseCode, byte[] binaryResponseHeaders,
                        byte[] body, String reasonPhrase);
    void fulfillRequest(RequestId requestId, int responseCode, List<HeaderEntry> responseHeaders,
                        byte[] body, String reasonPhrase);


    void failRequest(RequestId requestId, String errorReason);
    CompletionStage<Void> failRequestAsync(RequestId requestId, String errorReason);


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
            Page.FrameId frameId,
            Network.ResourceType resourceType,
            String responseErrorReason,
            Integer responseStatusCode,
            String responseStatusText,
            List<HeaderEntry> responseHeaders,
            Network.RequestId networkId,
            RequestId redirectedRequestId
    ) {
        public boolean isResponseStage() {
            return responseStatusCode != null || responseErrorReason != null;
        }
    }

    record HeaderEntry(String name, String value) {
    }
}
