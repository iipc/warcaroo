package org.netpreserve.warcbot.util;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.netpreserve.urlcanon.ParsedUrl;

import java.net.URI;

/**
 * URL type which caches parsing.
 */
public class Url {
    private final String url;
    private URI uri;
    private ParsedUrl parsedUrl;

    @JsonCreator
    public Url(String url) {
        this.url = url;
    }

    private Url(ParsedUrl parsedUrl) {
        this(parsedUrl.toString());
        this.parsedUrl = parsedUrl;
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
        return parse().getHost();
    }

    public String scheme() {
        return parse().getScheme();
    }

    @JsonValue
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

    private ParsedUrl parse() {
        if (parsedUrl == null) {
            parsedUrl = ParsedUrl.parseUrl(url);
        }
        return parsedUrl;
    }

    public String pathAndQuery() {
        ParsedUrl parsed = parse();
        return parsed.getPath() + parsed.getQuestionMark() + parsed.getQuery();
    }

    public String hostAndPort() {
        ParsedUrl parsed = parse();
        return parsed.getHost() + parsed.getColonBeforePort() + parsed.getPort();
    }

    public Url withPath(String path) {
        ParsedUrl parsed = parse();
        return new Url(parsed.getScheme() + parsed.getColonAfterScheme() + parsed.getSlashes() +
                       parsed.getHost() + parsed.getColonBeforePort() + parsed.getPort() + path);
    }
}
