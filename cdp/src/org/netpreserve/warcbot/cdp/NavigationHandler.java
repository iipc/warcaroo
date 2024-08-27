package org.netpreserve.warcbot.cdp;

import org.netpreserve.warcbot.util.Url;

public interface NavigationHandler {
    /**
     * Called when the top-level frame attempts to navigate elsewhere (such as via JavaScript or meta refresh).
     *
     * @param url the target URL of the navigation.
     * @return true if the navigation is allowed to proceed, false to abort it.
     */
    boolean handle(Url url);
}
