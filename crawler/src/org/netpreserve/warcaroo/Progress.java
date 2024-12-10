package org.netpreserve.warcaroo;

import java.time.Instant;

public record Progress(int id, Instant date, long runtime, long discovered, long pending, long crawled, long failed,
                       long resources, long size) {
    public Progress withRuntime(long runtime) {
        return new Progress(id, date, runtime, discovered, pending, crawled, failed, resources, size);
    }
}
