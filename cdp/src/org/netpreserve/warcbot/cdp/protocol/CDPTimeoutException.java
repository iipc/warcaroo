package org.netpreserve.warcaroo.cdp.protocol;

public class CDPTimeoutException extends CDPException {
    public CDPTimeoutException(String message) {
        super(0, message);
        actuallyFillInStackTrace();
    }
}
