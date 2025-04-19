package org.netpreserve.warcaroo;

import org.netpreserve.warcaroo.config.ResourcesConfig;
import org.netpreserve.warcaroo.config.ScopeConfig;
import org.netpreserve.warcaroo.config.SeedConfig;
import org.netpreserve.warcaroo.util.Url;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class Scope implements Predicate<Url> {
    private final UrlMatcher.Multi includes = new UrlMatcher.Multi();
    private final UrlMatcher.Multi excludes = new UrlMatcher.Multi();

    public Scope(List<SeedConfig> seeds, ScopeConfig config) {
        if (seeds != null) {
            for (var seed: seeds) {
                includes.add(seed.toUrlMatcher());
            }
        }
        if (config != null) {
            if (config.include() != null) {
                includes.addAll(config.include());
            }
            if (config.exclude() != null) {
                excludes.addAll(config.exclude());
            }
        }
    }

    public Scope(ResourcesConfig config) {
        includes.addAll(config.include());
        excludes.addAll(config.exclude());
    }

    @Override
    public boolean test(Url url) {
        if (excludes.test(url)) return false;
        if (includes.test(url)) return true;
        return false;
    }

    public void dump() {
        System.out.println("Scope:");
        System.out.println("  includes:");
        includes.dump();
        System.out.println("  excludes:");
        excludes.dump();
    }
}
