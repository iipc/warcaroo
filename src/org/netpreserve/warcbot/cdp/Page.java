package org.netpreserve.warcbot.cdp;

import java.util.function.Consumer;

public interface Page {
    Navigate navigate(String url);

    void resetNavigationHistory();

    void close();

    void enable();

    void onLoadEventFired(Consumer<LoadEventFired> handler);

    record Navigate(String frameId, String loaderId, String errorText) {
    }

    record LoadEventFired(double timestamp) {
    }
}
