package org.netpreserve.warcbot;

import java.net.URI;

public class Url {
    private final String url;
    private URI uri;

    public Url(String url) {
        this.url = url;
    }

    public static Url orNull(String url) {
        if (url == null) return null;
        return new Url(url);
    }

    public synchronized URI toURI() {
        if (uri == null) {
            uri = URI.create(url);
        }
        return uri;
    }

    public String host() {
        return toURI().getHost();
    }

    public String scheme() {
        return toURI().getScheme();
    }

    public String toString() {
        return url;
    }

    private static boolean startsWithIgnoreCase(String str, String prefix) {
        return str.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    public boolean isHttp() {
        return startsWithIgnoreCase(url, "http:") ||
               startsWithIgnoreCase(url, "https:");
    }

    public Url withoutFragment() {
        int i = url.indexOf('#');
        if (i == -1) {
            return this;
        }
        return new Url(url.substring(0, i));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Url url1 = (Url) o;
        return url.equals(url1.url);
    }

    @Override
    public int hashCode() {
        return url.hashCode();
    }
}
