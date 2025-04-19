package org.netpreserve.warcaroo.util;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import de.malkusch.whoisServerList.publicSuffixList.PublicSuffixList;
import de.malkusch.whoisServerList.publicSuffixList.PublicSuffixListFactory;
import org.netpreserve.urlcanon.Canonicalizer;
import org.netpreserve.urlcanon.ParsedUrl;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * URL type which caches parsing.
 */
public class Url {
    private static final PublicSuffixList publicSuffixList = new PublicSuffixListFactory().build();
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

    public synchronized URI toURI() throws URISyntaxException {
        if (uri == null) {
            uri = new URI(url);
        }
        return uri;
    }

    public String host() {
        return parse().getHost();
    }

    public String rhost() {
        return reverseHost(host());
    }

    public String domain() {
        String domain = publicSuffixList.getRegistrableDomain(host());
        if (domain == null) return host();
        return domain;
    }

    public String rdomain() {
        return reverseHost(domain());
    }

    public static String reverseHost(String host) {
        if (host == null) return null;
        if (host.startsWith("[")) return host;
        if (parseIpv4(host) != -1) return host;
        var builder = new StringBuilder();
        String[] segments = host.split("\\.");
        for (int i = segments.length - 1; i >= 0; i--) {
            builder.append(segments[i]);
            builder.append(",");
        }
        return builder.toString();
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

    public Url whatwg() {
        ParsedUrl copy = new ParsedUrl(parse());
        Canonicalizer.WHATWG.canonicalize(copy);
        return new Url(copy);
    }

    private static long parseIpv4(String host) {
        long ipv4 = 0;
        int startOfPart = 0;

        if (host.isEmpty()) {
            return -1;
        }

        for (int i = 0;; i++) {
            // find the end of this part
            int endOfPart = host.indexOf(".", startOfPart);
            if (endOfPart == -1) {
                endOfPart = host.length();
            }

            // if there's more than 4 return failure
            if (i >= 4) {
                return -1;
            }

            long part;
            if (startOfPart == endOfPart) { // treat empty parts as zero
                part = 0;
            } else {
                part = parseIpv4Num(host, startOfPart, endOfPart);
                if (part == -1) return -1; // not a number
            }

            // if this is the last part (or second-last part and last part is empty)
            if (endOfPart >= host.length() - 1) {
                if (part >= (1L << (8 * (4 - i)))) {
                    return -1; // too big
                }

                // 1.2 => 1.0.0.2
                ipv4 <<= 8 * (4 - i);
                ipv4 += part;
                return ipv4;
            }

            // if any but the last item is larger than 255 return failure
            if (part > 255) {
                return -1;
            }
            ipv4 = ipv4 * 256 + part;
            startOfPart = endOfPart + 1;
        }
    }

    private static long parseIpv4Num(String host, int start, int end) {
        int radix = 10;
        if (end - start >= 2 && host.charAt(start) == '0') {
            char c = host.charAt(start + 1);
            if (c == 'x' || c == 'X') {
                radix = 16;
                start += 2;
            } else {
                radix = 8;
                start++;
            }
        }
        try {
            return Long.parseUnsignedLong(host, start, end, radix);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public String path() {
        return parse().getPath();
    }

    public Url directoryPrefix() {
        ParsedUrl parsed = parse();
        String path = parsed.getPath();
        path = path.isEmpty() ? "/" : path.replaceFirst("/[^/]*$", "/");
        ParsedUrl copy = new ParsedUrl(parsedUrl);
        copy.setPath(path);
        copy.setQuery("");
        copy.setHashSign("");
        copy.setFragment("");
        copy.setQuestionMark("");
        return new Url(copy);
    }

    public boolean startsWith(Url prefix) {
        ParsedUrl parsed = parse();
        ParsedUrl prefixParsed = prefix.parse();
        return parsed.getScheme().equals(prefixParsed.getScheme()) &&
               parsed.getHost().equals(prefixParsed.getHost()) &&
               parsed.getPort().equals(prefixParsed.getPort()) &&
               parsed.getPath().startsWith(prefixParsed.getPath());
    }
}
