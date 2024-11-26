package org.netpreserve.warcaroo.cdp;

import org.netpreserve.warcaroo.util.Url;

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
