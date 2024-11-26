package org.netpreserve.warcaroo.cdp.protocol;

public class CDPException extends RuntimeException {
    private final int code;

    public CDPException(int code, String message) {
        super(message + " [" + code + "]");
        this.code = code;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }

    public void actuallyFillInStackTrace() {
        super.fillInStackTrace();
    }

    public int getCode() {
        return code;
    }
}
