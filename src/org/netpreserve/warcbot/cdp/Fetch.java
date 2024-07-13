package org.netpreserve.warcbot.cdp;

import org.netpreserve.jwarc.HttpRequest;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface Fetch {
    void enable(List<RequestPattern> patterns);
    void disable();

    void continueRequest(String requestId, boolean interceptResponse);
    void continueResponse(String requestId);
    Network.ResponseBody getResponseBody(String requestId);

    void onRequestPaused(Consumer<RequestPaused> handler);

    record RequestPattern(
            String urlPattern,
            String resourceType,
            String requestStage
    ){
    }

    record RequestPaused(
            String requestId,
            Network.Request request,
            String frameId,
            String resourceType,
            String responseErrorReason,
            Integer responseStatusCode,
            String responseStatusText,
            List<HeaderEntry> responseHeaders,
            String networkId,
            String redirectedRequestId
    ) {
        public boolean isResponseStage() {
            return responseStatusCode != null || responseErrorReason != null;
        }
    }

    record HeaderEntry(String name, String value) {
    }
}
