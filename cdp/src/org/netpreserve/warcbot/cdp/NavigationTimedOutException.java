package org.netpreserve.warcbot.cdp;

public class NavigationTimedOutException extends NavigationException {
    public NavigationTimedOutException(String message) {
        super(message);
    }
}
