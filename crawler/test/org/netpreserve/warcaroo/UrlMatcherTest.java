package org.netpreserve.warcaroo;

import org.junit.jupiter.api.Test;
import org.netpreserve.warcaroo.util.Url;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class UrlMatcherTest {
    @Test
    public void testMulti() {
        // Test empty constructor
        UrlMatcher.Multi emptyMatcher = new UrlMatcher.Multi();
        assertFalse(emptyMatcher.test(new Url("https://example.com")));
        
        // Test adding host matcher
        UrlMatcher.Multi hostMatcher = new UrlMatcher.Multi();
        hostMatcher.add(new UrlMatcher.Host("example.com"));
        assertTrue(hostMatcher.test(new Url("https://example.com")));
        assertTrue(hostMatcher.test(new Url("https://example.com/path")));
        assertTrue(hostMatcher.test(new Url("http://EXAMPLE.COM"))); // Case insensitive host
        assertFalse(hostMatcher.test(new Url("https://subdomain.example.com")));
        assertFalse(hostMatcher.test(new Url("https://othersite.com")));
        
        // Test adding domain matcher
        UrlMatcher.Multi domainMatcher = new UrlMatcher.Multi();
        domainMatcher.add(new UrlMatcher.Domain("example.com"));
        assertTrue(domainMatcher.test(new Url("https://example.com")));
        assertTrue(domainMatcher.test(new Url("https://subdomain.example.com")));
        assertTrue(domainMatcher.test(new Url("https://sub.sub.example.com")));
        assertFalse(domainMatcher.test(new Url("https://othersite.com")));
        
        // Test adding exact matcher
        UrlMatcher.Multi exactMatcher = new UrlMatcher.Multi();
        exactMatcher.add(new UrlMatcher.Exact(new Url("https://example.com/page.html")));
        assertTrue(exactMatcher.test(new Url("https://example.com/page.html")));
        assertFalse(exactMatcher.test(new Url("https://example.com/page.html?query=1")));
        assertFalse(exactMatcher.test(new Url("https://example.com")));

        // Test adding prefix matcher
        UrlMatcher.Multi prefixMatcher = new UrlMatcher.Multi();
        prefixMatcher.add(new UrlMatcher.Prefix(new Url("https://example.com/path")));
        assertTrue(prefixMatcher.test(new Url("https://example.com/path")));
        assertTrue(prefixMatcher.test(new Url("https://example.com/path/subpath")));
        assertTrue(prefixMatcher.test(new Url("https://example.com/path?query=1")));
        assertFalse(prefixMatcher.test(new Url("https://example.com/other")));
        
        // Test adding regex matcher
        UrlMatcher.Multi regexMatcher = new UrlMatcher.Multi();
        regexMatcher.add(new UrlMatcher.Regex("https://.*\\.example\\.com/.*"));
        assertTrue(regexMatcher.test(new Url("https://sub.example.com/page")));
        assertTrue(regexMatcher.test(new Url("https://another.example.com/page")));
        assertFalse(regexMatcher.test(new Url("https://example.com/page")));
        assertFalse(regexMatcher.test(new Url("http://sub.example.com/page")));
        
        // Test collection constructor
        List<UrlMatcher> matchers = new ArrayList<>();
        matchers.add(new UrlMatcher.Host("host.com"));
        matchers.add(new UrlMatcher.Domain("domain.com"));
        UrlMatcher.Multi collectionMatcher = new UrlMatcher.Multi(matchers);
        assertTrue(collectionMatcher.test(new Url("https://host.com")));
        assertTrue(collectionMatcher.test(new Url("https://domain.com")));
        assertTrue(collectionMatcher.test(new Url("https://sub.domain.com")));
        assertFalse(collectionMatcher.test(new Url("https://other.com")));
        
        // Test addAll method
        UrlMatcher.Multi addAllMatcher = new UrlMatcher.Multi();
        addAllMatcher.addAll(matchers);
        assertTrue(addAllMatcher.test(new Url("https://host.com")));
        assertTrue(addAllMatcher.test(new Url("https://domain.com")));
        assertTrue(addAllMatcher.test(new Url("https://sub.domain.com")));
        assertFalse(addAllMatcher.test(new Url("https://other.com")));
        
        // Test combining Multi matchers
        UrlMatcher.Multi combinedMatcher = new UrlMatcher.Multi();
        combinedMatcher.add(collectionMatcher);
        combinedMatcher.add(new UrlMatcher.Exact(new Url("https://specific.com/page")));
        assertTrue(combinedMatcher.test(new Url("https://host.com")));
        assertTrue(combinedMatcher.test(new Url("https://domain.com")));
        assertTrue(combinedMatcher.test(new Url("https://sub.domain.com")));
        assertTrue(combinedMatcher.test(new Url("https://specific.com/page")));
        assertFalse(combinedMatcher.test(new Url("https://specific.com/other")));
        assertFalse(combinedMatcher.test(new Url("https://unmatched.com")));
    }
    
    @Test
    public void testContainsPrefixOf() {
        // Test the static utility method containsPrefixOf
        NavigableSet<String> prefixes = new TreeSet<>();
        prefixes.add("https://example.com/");
        prefixes.add("https://test.com/");
        
        assertTrue(UrlMatcher.Multi.containsPrefixOf(prefixes, "https://example.com/page"));
        assertTrue(UrlMatcher.Multi.containsPrefixOf(prefixes, "https://example.com/"));
        assertFalse(UrlMatcher.Multi.containsPrefixOf(prefixes, "https://example.org/"));
        
        // Test with empty set
        NavigableSet<String> emptySet = new TreeSet<>();
        assertFalse(UrlMatcher.Multi.containsPrefixOf(emptySet, "anything"));
        
        // Test with multiple potential matches where one is a prefix of another
        NavigableSet<String> nestedPrefixes = new TreeSet<>();
        nestedPrefixes.add("abc");
        nestedPrefixes.add("abcdef");
        
        assertTrue(UrlMatcher.Multi.containsPrefixOf(nestedPrefixes, "abcdefghijk"));
        assertTrue(UrlMatcher.Multi.containsPrefixOf(nestedPrefixes, "abcde"));
    }
}