    package org.netpreserve.warcaroo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.FileAppender;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sun.net.httpserver.HttpServer;
import org.netpreserve.warcaroo.cdp.protocol.CDPBase;
import org.netpreserve.warcaroo.config.JobConfig;
import org.netpreserve.warcaroo.util.Url;
import org.netpreserve.warcaroo.webapp.Webapp;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

import ch.qos.logback.classic.LoggerContext;

public class Warcaroo {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(Warcaroo.class);

    public static void main(String[] args) throws Exception {
        String host = "localhost";
        Integer port = null;
        Path jobDir = Path.of("data");
        var seeds = new ArrayList<Url>();
        boolean dumpConfig = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dump-config" -> dumpConfig = true;
                case "--host" -> host = args[++i];
                case "--job-dir", "-j" -> jobDir = Path.of(args[++i]);
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--help", "-h" -> {
                    System.out.println("Usage: warcaroo [URL...]");
                    System.out.println("Options:");
                    System.out.println("  -h, --help");
                    System.out.println("      --host HOST          Host for web UI");
                    System.out.println("  -j, --job-dir DIR        Directory for job data");
                    System.out.println("  -p, --port PORT          Port for web UI");
                    System.out.println("      --trace-cdp <file>   Write CDP trace to file");
                    System.exit(0);
                }
                case "--trace-cdp" -> {
                    startCdpTraceFile(args[++i]);
                }
                default -> {
                    if (args[i].startsWith("-")) {
                        System.err.println("Unknown option: " + args[i]);
                        System.exit(1);
                    }
                    seeds.add(new Url(args[i]));
                }
            }
        }

        var mapper = new ObjectMapper(new YAMLFactory())
                .findAndRegisterModules()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        Path configFile = jobDir.resolve("config.yaml");
        var configTree = mapper.readTree(Warcaroo.class.getResourceAsStream("config/defaults.yaml"));
        if (Files.exists(configFile)) {
            configTree = deepMerge(configTree, mapper.readTree(configFile.toFile()));
        }
        JobConfig config = mapper.treeToValue(configTree, JobConfig.class);
        if (dumpConfig) {
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config));
            System.exit(0);
        }

        Job job = new Job(jobDir, config);
        job.frontier().addUrls(seeds, 0, null);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                job.close();
            } catch (Exception e) {
                System.err.println("Error shutting down crawl: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }, "shutdown-hook"));


        if (port != null) {
            System.setProperty("sun.net.httpserver.nodelay", "true");
            var httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
            httpServer.createContext("/", new Webapp(job));
            httpServer.setExecutor(Executors.newCachedThreadPool());
            System.out.println("Listening on http://" + host + ":" + httpServer.getAddress().getPort() + "/");
            httpServer.start();
        } else {
            job.start();
        }
    }

    private static JsonNode deepMerge(JsonNode base, JsonNode override) {
        if (!base.isObject() || !override.isObject()) {
            // for simple values or arrays, always take override
            return override;
        }
        ObjectNode merged = ((ObjectNode) base).deepCopy();
        override.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode overrideValue = entry.getValue();
            if (merged.has(key)) {
                JsonNode baseValue = merged.get(key);
                merged.set(key, deepMerge(baseValue, overrideValue));
            } else {
                merged.set(key, overrideValue);
            }
        });
        return merged;
    }

    private static void startCdpTraceFile(String file) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        var encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %msg%n");
        encoder.start();

        var fileAppender = new FileAppender<ILoggingEvent>();
        fileAppender.setContext(context);
        fileAppender.setEncoder(encoder);
        fileAppender.setName("cdp-trace-file");
        fileAppender.setFile(file);
        fileAppender.start();

        var logger = (Logger) LoggerFactory.getLogger(CDPBase.class);
        logger.addAppender(fileAppender);
        if (logger.getEffectiveLevel().toInteger() != Level.TRACE_INT) {
            ThresholdFilter filter = new ThresholdFilter();
            filter.setLevel(logger.getEffectiveLevel().toString());
            filter.start();
            var stdoutAppender = (ConsoleAppender<ILoggingEvent>)context.getLogger(Logger.ROOT_LOGGER_NAME)
                    .getAppender("STDOUT");
            stdoutAppender.stop();
            stdoutAppender.addFilter(filter);
            stdoutAppender.start();
            logger.setLevel(Level.TRACE);
        }
    }
}
