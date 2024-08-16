    package org.netpreserve.warcbot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.FileAppender;
import com.sun.net.httpserver.HttpServer;
import org.netpreserve.warcbot.cdp.protocol.CDPBase;
import org.netpreserve.warcbot.webapp.Webapp;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

import ch.qos.logback.classic.LoggerContext;

public class WarcBot {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(WarcBot.class);

    public static void main(String[] args) throws Exception {
        Config config = new Config();
        String host = "127.0.0.1";
        Integer port = null;
        int verbosity = 0;

        boolean headless = CrawlSettings.DEFAULTS.headless();
        String userAgent = CrawlSettings.DEFAULTS.userAgent();
        int workers = 1;
        String warcPrefix = null;
        List<BrowserSettings> browsers = new ArrayList<>();
        String browserExecutable = null;
        String sshCommand = "ssh";
        String browserOptions = null;
        String cdpTraceFile = null;


        for (int i = 0; i < args.length; i++) {
            try {
                switch (args[i]) {
                    case "-h", "--help" -> {
                        System.out.printf("""
                                Usage: warcbot [options] seed-url...
                                
                                Options:
                                  --block REGEX             Block fetching of resources that match the specified REGEX pattern.
                                  --browser PATH            Set the path to the browser executable
                                  --browser-options OPTIONS Additional command-line options to pass to the browser.
                                  --crawl-delay MILLIS      Wait this long before crawling another page from the same queue.
                                  --include REGEX           Include pages that match the specified REGEX pattern in the crawl scope.
                                  --headless                Run the browser in headless mode.
                                  --host HOST               Set the hostname to bind the server.
                                  --port PORT               Set the port to bind the server.
                                  --seed-file FILE          Load seed URLs from the specified file.
                                  --ssh HOST                Run a browser on a remote server over SSH.
                                  --ssh-command COMMAND     Set the ssh command to use, including SSH command-line options.
                                  --trace-cdp               Enables detailed logging of messages to and from the browser.
                                  -A, --user-agent STR      Set the User-Agent string to identify the crawler to the server.
                                  --warc-prefix STR         Prefix used when naming WARC files.
                                  -w, --workers INT         Specify the number of browser windows to use per browser (default is %d).
                                  -v, --verbose             Increase verbosity of the output.
                                
                                Examples:
                                  warcbot --include "https?://([^/]+\\.)example\\.com/.*" -A "MyCrawler/1.0" -w 5
                                """, workers);
                        return;
                    }
                    case "--block" -> config.addBlock(args[++i]);
                    case "--browser" -> browserExecutable = args[++i];
                    case "--browser-options" -> browserOptions = args[++i];
                    case "--crawl-delay" -> config.setCrawlDelay(Integer.parseInt(args[++i]));
                    case "--include" -> config.addInclude(args[++i]);
                    case "--headless" -> headless = true;
                    case "--host" -> host = args[++i];
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--seed-file", "--seedFile" -> config.loadSeedFile(Path.of(args[++i]));
                    case "--ssh" -> browsers.add(new BrowserSettings(args[++i], browserExecutable, browserOptions, Arrays.asList((sshCommand + " " + args[i]).split(" ")), workers, true));
                    case "--ssh-command" -> sshCommand = args[++i];
                    case "--trace-cdp" -> ((Logger)LoggerFactory.getLogger("org.netpreserve.warcbot.cdp.protocol.CDPBase")).setLevel(Level.TRACE);
                    case "--trace-cdp-file" -> cdpTraceFile = args[++i];
                    case "-A", "--user-agent", "--userAgent" -> userAgent = args[++i];
                    case "--warc-prefix", "--warcPrefix" -> warcPrefix = args[++i];
                    case "-w", "--workers" -> workers = Integer.parseInt(args[++i]);
                    case "-v", "--verbose" -> verbosity++;
                    default -> {
                        if (args[i].startsWith("-")) {
                            System.err.println("warcbot: unknown option " + args[i]);
                            System.exit(1);
                        }
                        config.addSeed(args[i]);
                    }
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                System.err.println("warcbot: Missing value for option " + args[i - 1]);
                System.exit(1);
            } catch (NumberFormatException e) {
                System.err.println("warcbot: Invalid number format for option " + args[i - 1]);
                System.exit(1);
            } catch (IllegalArgumentException e) {
                System.err.println("warcbot: " + e.getMessage() + " for option " + args[i - 1]);
                System.exit(1);
            }
        }

        if (verbosity >= 2) {
            ((Logger)LoggerFactory.getLogger("org.netpreserve.warcbot")).setLevel(Level.TRACE);
        } else if (verbosity == 1) {
            ((Logger)LoggerFactory.getLogger("org.netpreserve.warcbot")).setLevel(Level.DEBUG);
        }

        if (cdpTraceFile != null) {
            startCdpTraceFile(cdpTraceFile);
        }

        if (workers > 0) {
            browsers.add(new BrowserSettings("Local", browserExecutable, browserOptions, null, workers, headless));
        }

        config.setBrowsers(browsers);
        config.setCrawlSettings(new CrawlSettings(userAgent, headless, null, null,
                null, null, null, null, null,
                null, warcPrefix));
        Crawl crawl = new Crawl(Path.of("data"), config);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                crawl.close();
            } catch (Exception e) {
                System.err.println("Error shutting down crawl: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }, "shutdown-hook"));


        if (port != null) {
            System.setProperty("sun.net.httpserver.nodelay", "true");
            var httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
            httpServer.createContext("/", new Webapp(crawl));
            httpServer.setExecutor(Executors.newCachedThreadPool());
            System.out.println("Listening on http://" + host + ":" + httpServer.getAddress().getPort() + "/");
            httpServer.start();
        } else {
            crawl.start();
        }
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
