package org.netpreserve.warcbot.cdp;

import org.netpreserve.warcbot.util.Url;

public class NavigationException extends Exception {
    protected final Url url;

    public NavigationException(Url url, String message) {
        super(message + " for " + url);
        this.url = url;
    }

    public Url url() {
        return url;
    }
}
