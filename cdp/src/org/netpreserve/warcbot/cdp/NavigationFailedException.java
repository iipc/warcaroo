package org.netpreserve.warcbot.cdp;

import org.netpreserve.warcbot.util.Url;

public class NavigationFailedException extends NavigationException {
    private final String errorText;

    public NavigationFailedException(Url url, String errorText) {
        super(url, errorText);
        this.errorText = errorText;
    }

    public String errorText() {
        return errorText;
    }
}
