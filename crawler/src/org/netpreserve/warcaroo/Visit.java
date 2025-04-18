package org.netpreserve.warcaroo;

import org.netpreserve.warcaroo.config.JobConfig;
import org.netpreserve.warcaroo.util.Url;

import java.util.List;

/**
 * Alternative entrypoint for testing visiting a single page.
 */
public class Visit {
    public static void main(String[] args) throws Exception {
        Url url = new Url(args[0]);
        try (var browserManager = new BrowserManager()) {
            var worker = new Worker("visit", browserManager, null, null, null, null,
                    new Job(null, null));
            var visit = worker.visit(url);

            System.out.println("outlinks:");
            visit.outlinks().forEach(outlink -> System.out.println("- " + outlink.toMetadataString()));
        }
    }
}
