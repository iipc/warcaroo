package org.netpreserve.warcbot;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class Config {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<String> seeds = new ArrayList<>();
    private final List<Pattern> includes = new ArrayList<>();
    private String userAgent = "warcbot";
    private int workers = 1;

    public int getWorkers() {
        return workers;
    }

    public void setWorkers(int workers) {
        if (workers <= 0) throw new IllegalArgumentException("Need at least one worker");
        this.workers = workers;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        Objects.requireNonNull(userAgent, "User-agent can't be null");
        this.userAgent = userAgent;
    }

    public void addInclude(String regex) {
        includes.add(Pattern.compile(regex));
    }

    public void addSeed(String url) {
        seeds.add(url);
        var prefix = url.replaceFirst("/[^/]*$", "/");
        includes.add(Pattern.compile(Pattern.quote(prefix) + "(?:/|)"));
    }

    public List<String> getSeeds() {
        return seeds;
    }

    @JsonIgnore
    public Predicate<String> getScope() {
        var scope = (Predicate<String>) url -> false;
        for (var include: includes) {
            scope = scope.or(include.asPredicate());
        }
        return scope;
    }

    public List<Pattern> getIncludes() {
        return includes;
    }


    public void save(Path path) throws IOException {
        MAPPER.writeValue(path.toFile(), this);
    }

    public void load(Path path) throws IOException {
        MAPPER.readerForUpdating(this).readValue(path.toFile());
    }
}
