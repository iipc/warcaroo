package org.netpreserve.warcaroo.config;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.netpreserve.warcaroo.util.jackson.ShellCommandDeserializer;

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
        @JsonDeserialize(using = ShellCommandDeserializer.class)
        List<String> options,
        @JsonDeserialize(using = ShellCommandDeserializer.class)
        List<String> shell,
        int workers
) {
}
