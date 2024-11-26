package org.netpreserve.warcaroo.cdp.protocol;

import org.netpreserve.warcaroo.cdp.domains.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

public class CDPSession extends CDPBase {
    private static final Logger log = LoggerFactory.getLogger(CDPSession.class);
    private final String sessionId;
    private final String targetId;
    private final CDPClient client;

    public CDPSession(CDPClient client, String sessionId, String targetId) {
        super();
        this.client = client;
        this.sessionId = sessionId;
        this.targetId = targetId;
        client.sessions.put(sessionId, this);
    }

    @Override
    protected void sendCommandMessage(long commandId, String method, Map<String, Object> params) throws IOException {
        client.rpc.send(new RPC.Command(commandId, method, params, sessionId));
    }

    @Override
    protected long nextCommandId() {
        return client.nextCommandId();
    }

    @Override
    public void close() {
        try {
            client.domain(Target.class).closeTarget(targetId);
        } catch (Exception e) {
            log.warn("Error closing session target", e);
        }
        client.sessions.remove(sessionId);
        super.close();
    }

    public String targetId() {
        return targetId;
    }
}
