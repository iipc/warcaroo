package org.netpreserve.warcaroo.config;

import java.util.List;

/**
 * Configuration for a browser.
 *
 * @param id          unique identifier for this browser instance
 * @param executable  binary to invoke (e.g. "google-chrome-stable")
 * @param options     command‑line options
 * @param shell       remote shell command (e.g. ["ssh", "user@host"])
 * @param workers     number of simultaneous windows to manage
 */
public record BrowserConfig(
        String id,
        String executable,
        List<String> options,
        List<String> shell,
        int workers
) {
}
