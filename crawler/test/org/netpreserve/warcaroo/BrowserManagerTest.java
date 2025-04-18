package org.netpreserve.warcaroo;

import org.junit.jupiter.api.Test;
import org.netpreserve.warcaroo.config.BrowserConfig;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class BrowserManagerTest {
    @Test
    public void test() throws IOException {
        try (var browserManager = new BrowserManager()) {
            browserManager.close(); // close current browser
            var version = browserManager.version(); // should automatically start a new one
            assertNotNull(version);
        }
    }
}