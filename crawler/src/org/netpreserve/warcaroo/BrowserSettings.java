package org.netpreserve.warcaroo;

import java.util.List;

public record BrowserSettings(
        String id,
        String executable,
        String options,
        List<String> shell,
        int workers,
        boolean headless) {
    public BrowserSettings() {
        this("Local", null, null, null, 1, true);
    }
}
