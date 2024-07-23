package org.netpreserve.warcbot.cdp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jetbrains.annotations.Nullable;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public interface Network {
    void enable(Integer maxTotalBufferSize, Integer maxResourceBufferSize, Integer maxPostDataSize);

    ResponseBody getResponseBody(String requestId);

    void onRequestWillBeSent(Consumer<RequestWillBeSent> handler);

    void onLoadingFailed(Consumer<LoadingFailed> handler);

    void onLoadingFinished(Consumer<LoadingFinished> handler);

    void onRequestServedFromCache(Consumer<RequestServedFromCache> handler);

    void onRequestWillBeSentExtraInfo(Consumer<RequestWillBeSentExtraInfo> handler);

    void onResponseReceived(Consumer<ResponseReceived> handler);

    void onResponseReceivedExtraInfo(Consumer<ResponseReceivedExtraInfo> handler);

    void onDataReceived(Consumer<DataReceived> handler);

    class ResponseBody {
        private final byte[] body;

        @JsonCreator
        public ResponseBody(@JsonProperty("body") String body, @JsonProperty("base64Encoded") boolean base64Encoded) {
            if (base64Encoded) {
                this.body = Base64.getDecoder().decode(body);
            } else {
                this.body = body.getBytes();
            }
        }

        public byte[] body() {
            return body;
        }
    }

    record LoaderId(@JsonValue String value) {
        @JsonCreator
        public LoaderId {
            Objects.requireNonNull(value);
        }
    }

    record MonotonicTime(@JsonValue double value) {
        @JsonCreator
        public MonotonicTime {}
    }

    record RequestWillBeSent(
            String requestId,
            LoaderId loaderId,
            String documentURL,
            Request request,
            double timestamp,
            long wallTime,
            Initiator initiator,
            Response redirectResponse,
            String resourceType,
            String frameId) {
    }

    record RequestWillBeSentExtraInfo(
            String requestId,
            List<AssociatedCookie> associatedCookies,
            Map<String, String> headers
    ) {
    }

    record AssociatedCookie() {
    }

    record ResponseReceived(
            String requestId,
            LoaderId loaderId,
            double timestamp,
            String type,
            Response response,
            String frameId
    ) {
    }

    record ResponseReceivedExtraInfo(
            String requestId,
            Map<String, String> headers,
            int statusCode,
            String headersText
    ) {
    }

    record RequestServedFromCache(String requestId) {
    }

    record LoadingFinished(String requestId, long timestamp, long encodedDataLength) {
    }

    record LoadingFailed(
            String requestId,
            long timestamp,
            String type,
            String errorText,
            boolean canceled,
            String blockedReason) {

    }

    record Response(
            String url,
            int status,
            String statusText,
            Map<String, String> headers,
            String mimeType,
            String charset,
            Map<String, String> requestHeaders,
            boolean connectionReused,
            long connectionId,
            String remoteIPAddress,
            int remotePort,
            boolean fromDiskCache,
            boolean fromServiceWorker,
            boolean fromPrefetchCache,
            boolean fromEarlyHints,
            long encodedDataLength,
            ResourceTiming timing,
            long responseTime,
            String protocol) {
    }

    record ResourceTiming(
            double requestTime
    ){
    }

    record Request(
            String url,
            String urlFragment,
            String method,
            Map<String, String> headers,
            List<PostDataEntry> postDataEntries) {

        public byte[] body() {
            if (postDataEntries == null) return null;
            int length = 0;
            for (var entry : postDataEntries) {
                length += entry.bytes.length;
            }
            var body = new byte[length];
            int position = 0;
            for (var entry : postDataEntries) {
                System.arraycopy(entry.bytes, 0, body, position, entry.bytes.length);
                position += entry.bytes.length;
            }
            return body;
        }

        public boolean hasBody() {
            return postDataEntries != null && !postDataEntries.isEmpty();
        }
    }

    class PostDataEntry {
        private final byte[] bytes;

        public @JsonCreator PostDataEntry(@JsonProperty("bytes") String bytes) {
            this.bytes = Base64.getDecoder().decode(bytes);
        }

        public byte[] bytes() {
            return bytes;
        }
    }

    record Initiator() {
    }

    record DataReceived(String requestId, double timestamp, int dataLength, int encodedDataLength, @Nullable byte[] data) {
    }
}
