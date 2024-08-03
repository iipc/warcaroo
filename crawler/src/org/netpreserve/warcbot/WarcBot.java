    package org.netpreserve.warcbot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.sun.net.httpserver.HttpServer;
import org.netpreserve.warcbot.webapp.Webapp;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Executors;

public class WarcBot {

    public static void main(String[] args) throws Exception {
        Config config = new Config();
        String host = "127.0.0.1";
        Integer port = null;
        int verbosity = 0;

        boolean headless = CrawlSettings.DEFAULTS.headless();
        String userAgent = CrawlSettings.DEFAULTS.userAgent();
        int workers = CrawlSettings.DEFAULTS.workers();
        String warcPrefix = null;

        for (int i = 0; i < args.length; i++) {
            try {
                switch (args[i]) {
                    case "-h", "--help" -> {
                        System.out.printf("""
                                Usage: warcbot [options] seed-url...
                                
                                Options:
                                  --block REGEX             Block fetching of resources that match the specified REGEX pattern.
                                  --browser PATH            Set the path to the browser binary
                                  --crawl-delay MILLIS      Wait this long before crawling another page from the same queue.
                                  --include REGEX           Include pages that match the specified REGEX pattern in the crawl scope.
                                  --headless                Run the browser in headless mode.
                                  --host HOST               Set the hostname to bind the server.
                                  --port PORT               Set the port to bind the server.
                                  --seed-file FILE          Load seed URLs from the specified file.
                                  -A, --user-agent STR      Set the User-Agent string to identify the crawler to the server.
                                  --warc-prefix STR         Prefix used when naming WARC files.
                                  -w, --workers INT         Specify the number of browser and worker threads to use (default is %d).
                                  -v, --verbose             Increase verbosity of the output.
                                
                                Examples:
                                  warcbot --include "https?://([^/]+\\.)example\\.com/.*" -A "MyCrawler/1.0" -w 5
                                """, workers);
                        return;
                    }
                    case "--block" -> config.addBlock(args[++i]);
                    case "--browser" -> config.setBrowserBinary(args[++i]);
                    case "--crawl-delay" -> config.setCrawlDelay(Integer.parseInt(args[++i]));
                    case "--include" -> config.addInclude(args[++i]);
                    case "--headless" -> headless = true;
                    case "--host" -> host = args[++i];
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--seed-file", "--seedFile" -> config.loadSeedFile(Path.of(args[++i]));
                    case "--trace-cdp" -> ((Logger)LoggerFactory.getLogger("org.netpreserve.warcbot.cdp.protocol.CDPBase")).setLevel(Level.TRACE);
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

        config.setCrawlSettings(new CrawlSettings(workers, userAgent, headless, null, null,
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
            httpServer.start();
        } else {
            crawl.start();
        }
    }
}
