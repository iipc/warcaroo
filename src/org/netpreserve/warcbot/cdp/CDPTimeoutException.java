package org.netpreserve.warcbot.cdp;

public class CDPTimeoutException extends CDPException {
    public CDPTimeoutException(String message) {
        super(0, message);
    }
}
