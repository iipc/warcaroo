package org.netpreserve.warcaroo;

import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcRecord;
import org.netpreserve.jwarc.WarcRequest;
import org.netpreserve.jwarc.WarcResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;

class Index {
    private final NavigableSet<Entry> entries = new TreeSet<>();

    public void addWarc(Path file) throws IOException {
        try (WarcReader reader = new WarcReader(file)) {
            WarcResponse response = null;
            for (WarcRecord record : reader) {
                if (record instanceof WarcResponse) {
                    if (response != null) {
                        entries.add(new Entry(response, null, file, reader.position()));
                    }
                    response = (WarcResponse) record;
                } else if (record instanceof WarcRequest request
                           && response != null
                           && request.concurrentTo().contains(response.id())) {
                    entries.add(new Entry(response, request, file, reader.position()));
                    response = null;
                }
            }
        }
    }

    public Entry findClosest(String method, String url, Instant date) {
        Objects.requireNonNull(url);
        Objects.requireNonNull(date);

        Entry key = new Entry(method, url, date);
        Entry floor = entries.floor(key);
        Entry ceiling = entries.ceiling(key);

        Entry closest = null;
        long minDiff = Long.MAX_VALUE;

        if (floor != null && floor.url.equals(url)) {
            long diff = Math.abs(Duration.between(floor.date, date).toMillis());
            if (diff < minDiff) {
                minDiff = diff;
                closest = floor;
            }
        }

        if (ceiling != null && ceiling.url.equals(url)) {
            long diff = Math.abs(Duration.between(ceiling.date, date).toMillis());
            if (diff < minDiff) {
                closest = ceiling;
            }
        }

        return closest;
    }

    public record Entry(String method, String url, Instant date, Path file,
                         long position) implements Comparable<Entry> {

        public Entry(String method, String url, Instant date) {
            this(method, url, date, null, -1L);
        }

        public Entry(WarcResponse response, WarcRequest request, Path file, long position) throws IOException {
            this(request == null ? null : request.http().method(), response.target(), response.date(), file, position);
        }

        @Override
        public int compareTo(Entry o) {
            int cmp = this.method.compareTo(o.method);
            if (cmp != 0) return cmp;
            cmp = this.url.compareTo(o.url);
            if (cmp != 0) return cmp;
            return this.date.compareTo(o.date);
        }
    }
}
