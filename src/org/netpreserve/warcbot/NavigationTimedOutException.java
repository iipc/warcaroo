package org.netpreserve.warcbot;

public class NavigationTimedOutException extends NavigationException {
    public NavigationTimedOutException(String message) {
        super(message);
    }
}
