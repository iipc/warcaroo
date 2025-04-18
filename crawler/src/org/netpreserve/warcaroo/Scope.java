package org.netpreserve.warcaroo;

import org.netpreserve.warcaroo.config.MatchRule;
import org.netpreserve.warcaroo.config.ResourcesConfig;
import org.netpreserve.warcaroo.config.ScopeConfig;
import org.netpreserve.warcaroo.util.Url;

import java.util.List;
import java.util.function.Predicate;

public class Scope implements Predicate<Url> {
    private final List<MatchRule> includes;
    private final List<MatchRule> excludes;

    public Scope(ScopeConfig config) {
        if (config == null || config.include() == null) {
            includes = List.of(new MatchRule.All());
        } else {
            includes = config.include();
        }
        if (config == null || config.exclude() == null) {
            excludes = List.of();
        } else {
            excludes = config.exclude();
        }
    }

    public Scope(ResourcesConfig config) {
        includes = config.include();
        excludes = config.exclude();
    }

    @Override
    public boolean test(Url url) {
        for (var exclude: excludes) {
            if (exclude.matches(url)) return false;
        }
        for (var include: includes) {
            if (include.matches(url)) return true;
        }
        return false;
    }
}
