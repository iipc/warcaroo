package org.netpreserve.warcbot;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.netpreserve.warcbot.util.Url;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class Config {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<Url> seeds = new ArrayList<>();
    private final List<Pattern> includes = new ArrayList<>();
    private final List<Pattern> blocks = new ArrayList<>();
    private int crawlDelay = 2000;
    private String browserBinary;
    private volatile CrawlSettings crawlSettings = CrawlSettings.DEFAULTS;

    public void addInclude(String regex) {
        includes.add(Pattern.compile(regex));
    }

    public void addBlock(String regex) {
        blocks.add(Pattern.compile(regex));
    }

    public void addSeed(String url) {
        Url cleanUrl = new Url(url).whatwg();
        seeds.add(cleanUrl);
        var prefix = cleanUrl.toString().replaceFirst("/[^/]*$", "/");
        includes.add(Pattern.compile(Pattern.quote(prefix) + "(?:/|)"));
    }

    public List<Url> getSeeds() {
        return seeds;
    }

    @JsonIgnore
    public Predicate<String> getScope() {
        return patternsToPredicate(includes);
    }

    private static Predicate<String> patternsToPredicate(List<Pattern> patterns) {
        var predicate = (Predicate<String>) url -> false;
        for (var pattern: patterns) {
            predicate = predicate.or(pattern.asPredicate());
        }
        return predicate;
    }

    @JsonIgnore
    public Predicate<String> getBlockPredicate() {
        return patternsToPredicate(blocks);
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

    public int getCrawlDelay() {
        return crawlDelay;
    }

    public void setCrawlDelay(int crawlDelay) {
        if (crawlDelay <= 0) throw new IllegalArgumentException("Crawl delay can't be negative");
        this.crawlDelay = crawlDelay;
    }

    public void loadSeedFile(Path file) throws IOException {
        try (Stream<String> stream = Files.lines(file)) {
            stream.map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(this::addSeed);
        }
    }

    public String getBrowserBinary() {
        return browserBinary;
    }

    public void setBrowserBinary(String browserBinary) {
        this.browserBinary = browserBinary;
    }

    public CrawlSettings getCrawlSettings() {
        return crawlSettings;
    }

    public void setCrawlSettings(CrawlSettings crawlSettings) {
        this.crawlSettings = crawlSettings;
    }
}
