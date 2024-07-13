package org.netpreserve.warcbot.cdp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class CDPPipe implements CDPConnection {
    private static final Logger log = LoggerFactory.getLogger(CDPPipe.class);
    private static final Pattern BIG_STRING = Pattern.compile("\"([^\"]{20})[^\"]{40,}([^\"]{20})", Pattern.DOTALL | Pattern.MULTILINE);
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final Consumer<CDPClient.Result> messageHandler;

    public CDPPipe(InputStream inputStream, OutputStream outputStream, Consumer<CDPClient.Result> messageHandler) {
        this.inputStream = new BufferedInputStream(inputStream, 8192);
        this.outputStream = outputStream;
        this.messageHandler = messageHandler;
        var thread = new Thread(this::run, "CDPPipe");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        try {
            while (true) {
                var buffer = new ByteArrayOutputStream();
                while (true) {
                    int b = inputStream.read();
                    if (b < 0) return;
                    if (b == 0) break;
                    buffer.write(b);
                }
                log.atTrace().log(() -> "<- " + BIG_STRING.matcher(buffer.toString()).replaceAll("\"$1...$2\""));
                var message = CDPClient.json.readValue(buffer.toByteArray(), CDPClient.Result.class);
                messageHandler.accept(message);
            }
        } catch (IOException e) {
            log.error("Error reading CDPPipe", e);
        }
    }

    @Override
    public void send(CDPClient.Call message) throws IOException  {
        log.trace("-> {}", message);
        CDPClient.json.writeValue(outputStream, message);
        outputStream.write(0);
        outputStream.flush();
    }

    @Override
    public void close() {
        try {
            outputStream.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
