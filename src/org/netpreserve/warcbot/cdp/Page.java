package org.netpreserve.warcbot.cdp;

public interface Page {
    Navigate navigate(String url);

    void resetNavigationHistory();

    record Navigate(String frameId, String loaderId, String errorText) {
    }
}
