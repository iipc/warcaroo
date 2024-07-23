package org.netpreserve.warcbot;

import org.netpreserve.jwarc.HttpResponse;
import org.netpreserve.jwarc.MessageHeaders;
import org.netpreserve.warcbot.cdp.domains.Network;

import java.io.IOException;

import static java.nio.charset.StandardCharsets.UTF_8;

public interface RequestHandler {
    Response handle(Network.Request request);

    record Response(int status, String reason, MessageHeaders headers, byte[] body) {
        public Response(HttpResponse httpResponse) throws IOException {
            this(httpResponse.status(), httpResponse.reason(), httpResponse.headers(),
                    httpResponse.body().stream().readAllBytes());
        }

        public Response(int status, String body) {
            this(status, null, null, body.getBytes(UTF_8));
        }
    }
}
