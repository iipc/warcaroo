package org.netpreserve.warcbot.cdp.protocol;

import java.io.IOException;
import java.util.Map;

public class CDPSession extends CDPBase {
    private final String sessionId;
    private final CDPClient client;

    public CDPSession(CDPClient client, String sessionId) {
        super();
        this.client = client;
        this.sessionId = sessionId;
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
        client.sessions.remove(sessionId);
        super.close();
    }
}
