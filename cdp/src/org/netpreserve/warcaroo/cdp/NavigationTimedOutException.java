package org.netpreserve.warcaroo.cdp;

import org.netpreserve.warcaroo.util.Url;

public class NavigationTimedOutException extends NavigationException {
    public NavigationTimedOutException(Url url, String message) {
        super(url, message);
    }
}
