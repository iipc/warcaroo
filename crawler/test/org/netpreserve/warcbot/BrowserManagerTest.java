package org.netpreserve.warcbot;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class BrowserManagerTest {
    @Test
    public void test() throws IOException {
        var settings = new BrowserSettings("test", null, null, null, 1, true);
        try (var browserManager = new BrowserManager(settings)) {
            browserManager.close(); // close current browser
            var version = browserManager.version(); // should automatically start a new one
            assertNotNull(version);
        }
    }
}