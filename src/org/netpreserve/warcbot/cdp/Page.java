package org.netpreserve.warcbot.cdp;

public interface Page {
    Navigate navigate(String url);

    record Navigate(String frameId, String loaderId, String errorText) {
    }
}
