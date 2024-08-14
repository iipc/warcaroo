package org.netpreserve.warcbot;

import java.util.List;

public record BrowserSettings(
        String id,
        String executable,
        String options,
        List<String> shell,
        int workers,
        boolean headless) {
}
