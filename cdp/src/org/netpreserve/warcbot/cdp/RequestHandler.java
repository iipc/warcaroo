package org.netpreserve.warcbot.cdp;

import org.netpreserve.warcbot.cdp.domains.Network;

import java.net.http.HttpHeaders;

import static java.nio.charset.StandardCharsets.UTF_8;

public interface RequestHandler {
    Response handle(Network.Request request);

    record Response(int status, String reason, HttpHeaders headers, byte[] body) {
        public Response(int status, String body) {
            this(status, null, null, body.getBytes(UTF_8));
        }
    }
}
