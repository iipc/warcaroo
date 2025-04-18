package org.netpreserve.warcaroo.cdp.domains;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jetbrains.annotations.Nullable;
import org.netpreserve.warcaroo.cdp.protocol.Unwrap;
import org.netpreserve.warcaroo.util.Url;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public interface Network {
    void enable(Integer maxTotalBufferSize, Integer maxResourceBufferSize, Integer maxPostDataSize);

    ResponseBody getResponseBody(RequestId requestId);

    CompletionStage<ResponseBody> getResponseBodyAsync(RequestId requestId);

    void onRequestWillBeSent(Consumer<RequestWillBeSent> handler);

    void onLoadingFailed(Consumer<LoadingFailed> handler);

    void onLoadingFinished(Consumer<LoadingFinished> handler);

    void onRequestServedFromCache(Consumer<RequestServedFromCache> handler);

    void onRequestWillBeSentExtraInfo(Consumer<RequestWillBeSentExtraInfo> handler);

    void onResponseReceived(Consumer<ResponseReceived> handler);

    void onResponseReceivedExtraInfo(Consumer<ResponseReceivedExtraInfo> handler);

    void onDataReceived(Consumer<DataReceived> handler);

    @Unwrap("bufferedData")
    CompletableFuture<byte[]> streamResourceContent(RequestId requestId);

    void setRequestInterception(List<RequestPattern> patterns);

    void onRequestIntercepted(Consumer<RequestIntercepted> handler);

    void continueInterceptedRequest(InterceptionId interceptionId);

    @Unwrap("resource")
    LoadNetworkResourcePageResult loadNetworkResource(Url url, Page.FrameId frameId, LoadNetworkResourceOptions options);

    record LoadNetworkResourceOptions(boolean disableCache, boolean includeCredentials) {
    }

    record LoadNetworkResourcePageResult(boolean success, Integer netError, String netErrorName, Integer httpStatusCode,
                                         IO.StreamHandle stream, Headers headers) {}


    record RequestIntercepted(InterceptionId interceptionId, RequestId requestId) {
    }

    record RequestPattern() {
    }

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

    record InterceptionId(@JsonValue String value) {
        @JsonCreator
        public InterceptionId {
            Objects.requireNonNull(value);
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
        public MonotonicTime {
        }
    }

    record RequestId(@JsonValue String value) {
        @JsonCreator
        public RequestId {
            Objects.requireNonNull(value);
        }

        public String toString() {
            return value;
        }
    }

    record MillisSinceEpoch(@JsonValue double value) {
        @JsonCreator
        public MillisSinceEpoch(Number value) {
            this(value.doubleValue());
        }

        public Instant toInstant() {
            return Instant.ofEpochSecond((long)(value / 1000.0), (long)((value % 1000) * 1_000_000));
        }
    }

    record ResourceType(@JsonValue String value) {
        @JsonCreator
        public ResourceType {
            Objects.requireNonNull(value);
        }

        public boolean isDocument() {
            return value.equals("Document");
        }

        public boolean isDownload() {
            return value.equals("Download");
        }
    }

    record RequestWillBeSent(
            RequestId requestId,
            LoaderId loaderId,
            String documentURL,
            Request request,
            double timestamp,
            long wallTime,
            Initiator initiator,
            Response redirectResponse,
            ResourceType type,
            Page.FrameId frameId) {
    }

    record RequestWillBeSentExtraInfo(
            RequestId requestId,
            List<AssociatedCookie> associatedCookies,
            Map<String, String> headers
    ) {
    }

    record AssociatedCookie() {
    }

    record ResponseReceived(
            RequestId requestId,
            LoaderId loaderId,
            double timestamp,
            ResourceType type,
            Response response,
            Page.FrameId frameId
    ) {
    }

    record ResponseReceivedExtraInfo(
            RequestId requestId,
            Map<String, String> headers,
            int statusCode,
            String headersText
    ) {
    }

    record RequestServedFromCache(RequestId requestId) {
    }

    record LoadingFinished(RequestId requestId, long timestamp, long encodedDataLength) {
    }

    record LoadingFailed(
            RequestId requestId,
            long timestamp,
            String type,
            String errorText,
            boolean canceled,
            String blockedReason) {

    }

    record Response(
            Url url,
            int status,
            String statusText,
            Headers headers,
            String mimeType,
            String charset,
            Headers requestHeaders,
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
            MillisSinceEpoch responseTime,
            String protocol) {
    }

    class Headers extends TreeMap<String, String> {
        public Headers() {
            super(String.CASE_INSENSITIVE_ORDER);
        }
    }

    record ResourceTiming(
            double requestTime
    ) {
    }

    record Request(
            Url url,
            String urlFragment,
            String method,
            Headers headers,
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

    record DataReceived(RequestId requestId, double timestamp, int dataLength, int encodedDataLength, String data) {
        public byte[] decodeData() {
            return data == null ? null : Base64.getDecoder().decode(data);
        }
    }
}
