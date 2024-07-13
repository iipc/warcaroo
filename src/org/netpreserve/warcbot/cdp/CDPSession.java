package org.netpreserve.warcbot.cdp;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.function.Consumer;

public class CDPSession extends CDPBase {
    private final String sessionId;
    private final CDPClient client;

    public CDPSession(CDPClient client, String sessionId) {
        this.client = client;
        this.sessionId = sessionId;
    }

    @Override
    public <T> void addListener(Class<T> eventClass, Consumer<T> callback) {
        client.addSessionListener(sessionId, eventClass, callback);
    }

    @Override
    protected <T> T send(String method, Map<String, Object> params, Type returnType) {
        return client.send(method, params, returnType, sessionId);
    }
}
