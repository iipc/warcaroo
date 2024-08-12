package org.netpreserve.warcbot.cdp.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class CDPClient extends CDPBase implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CDPClient.class);
    private final AtomicLong idSeq = new AtomicLong();
    final Map<String, CDPSession> sessions = new ConcurrentHashMap<>();
    final RPC rpc;

    public CDPClient(URI devtoolsUrl) throws IOException {
        this.rpc = new RPC.Socket(devtoolsUrl, this::handleMessage);
    }

    public CDPClient(InputStream inputStream, OutputStream outputStream) {
        this.rpc = new RPC.Pipe(inputStream, outputStream, this::handleMessage);
    }

    @Override
    public void close() {
        rpc.close();
        super.close();
    }

    protected void handleMessage(RPC.ServerMessage message) {
        if (message.sessionId() == null) {
            super.handleMessage(message);
        } else {
            var session = sessions.get(message.sessionId());
            if (session != null) {
                session.handleMessage(message);
            } else {
                log.debug("Ignoring CDP message for unknown session: {}", message);
            }
        }
    }

    @Override
    protected void sendCommandMessage(long commandId, String method, Map<String, Object> params) throws IOException {
        rpc.send(new RPC.Command(commandId, method, params, null));
    }

    @Override
    protected long nextCommandId() {
        return idSeq.incrementAndGet();
    }
}
