package org.netpreserve.warcaroo.cdp.protocol;

public class CDPClosedException extends CDPException {
    public CDPClosedException() {
        super(0, "Browser connection closed");
    }
}
