package org.netpreserve.warcaroo;

import org.apache.commons.lang3.StringUtils;
import org.netpreserve.warcaroo.util.Url;


import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@JsonSubTypes({
        @JsonSubTypes.Type(value = UrlMatcher.Host.class),
        @JsonSubTypes.Type(value = UrlMatcher.Regex.class),
        @JsonSubTypes.Type(value = UrlMatcher.Domain.class),
        @JsonSubTypes.Type(value = UrlMatcher.Exact.class),
        @JsonSubTypes.Type(value = UrlMatcher.Prefix.class),
})
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
public sealed interface UrlMatcher extends Predicate<Url> {
    record Host(String host) implements UrlMatcher {
        @Override
        public boolean test(Url url) {
            return host.equalsIgnoreCase(url.host());
        }
    }

    record Domain(String domain) implements UrlMatcher {
        @Override
        public boolean test(Url url) {
            return domain.equalsIgnoreCase(url.host()) || StringUtils.endsWithIgnoreCase(url.host(), "." + domain);
        }
    }

    record Regex(String regex) implements UrlMatcher {
        @Override
        public boolean test(Url url) {
            return url.toString().matches(regex);
        }
    }

    record Exact(Url url) implements UrlMatcher {
        @Override
        public boolean test(Url url) {
            return url.equals(this.url);
        }
    }

    record Prefix(Url prefix) implements UrlMatcher {
        @Override
        public boolean test(Url url) {
            return url.startsWith(prefix);
        }
    }

    /**
     * A UrlMatcher that matches URLs against multiple other UrlMatchers.
     */
    final class Multi implements UrlMatcher {
        private final Set<String> urls = new HashSet<>();
        private final Set<String> hosts = new HashSet<>();
        private final NavigableSet<String> reversedDomainPrefixes = new TreeSet<>();
        private final NavigableSet<String> prefixes = new TreeSet<>();
        private final List<Pattern> regexes = new ArrayList<>();

        public Multi() {
        }

        public Multi(Collection<UrlMatcher> matchers) {
            addAll(matchers);
        }

        public void addAll(Collection<UrlMatcher> matchers) {
            for (var matcher : matchers) {
                add(matcher);
            }
        }

        public void add(UrlMatcher matcher) {
            switch (matcher) {
                case Host host -> hosts.add(host.host().toLowerCase(Locale.ROOT));
                case Domain domain -> {
                    String lowerCaseDomain = domain.domain().toLowerCase(Locale.ROOT);
                    hosts.add(lowerCaseDomain);
                    reversedDomainPrefixes.add(Url.reverseHost(lowerCaseDomain));
                }
                case Exact exact -> urls.add(exact.url.toString());
                case Prefix prefix -> prefixes.add(prefix.prefix().toString());
                case Regex regex -> regexes.add(Pattern.compile(regex.regex()));
                case Multi multi -> {
                    urls.addAll(multi.urls);
                    hosts.addAll(multi.hosts);
                    reversedDomainPrefixes.addAll(multi.reversedDomainPrefixes);
                    prefixes.addAll(multi.prefixes);
                    regexes.addAll(multi.regexes);
                }
            }
        }

        @Override
        public boolean test(Url url) {
            if (urls.contains(url.toString())) return true;
            if (!hosts.isEmpty()) {
                String lowerCaseHost = url.host().toLowerCase(Locale.ROOT);
                if (hosts.contains(lowerCaseHost)) return true;
                if (!reversedDomainPrefixes.isEmpty()) {
                    String reversedHost = Url.reverseHost(lowerCaseHost);
                    if (containsPrefixOf(reversedDomainPrefixes, reversedHost)) return true;
                }
            }
            if (containsPrefixOf(prefixes, url.toString())) return true;
            for (var regex : regexes) {
                if (regex.matcher(url.toString()).matches()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns true if the set contains a string that is a prefix of the given string.
         */
        public static boolean containsPrefixOf(NavigableSet<String> set, String s) {
            String candidate = set.floor(s);
            while (candidate != null) {
                if (s.startsWith(candidate)) {
                    return true;
                }
                candidate = set.lower(candidate);
            }
            return false;
        }

        public void dump() {
            System.out.println("    urls: " + urls);
            System.out.println("    hosts: " + hosts);
            System.out.println("    reversedDomainPrefixes: " + reversedDomainPrefixes);
            System.out.println("    prefixes: " + prefixes);
            System.out.println("    regexes: " + regexes);
        }
    }
}
