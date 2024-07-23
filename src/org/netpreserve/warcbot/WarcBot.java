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

        for (int i = 0; i < args.length; i++) {
            try {
                switch (args[i]) {
                    case "-h", "--help" -> {
                        System.out.printf("""
                                Usage: warcbot [options] seed-url...
                                
                                Options:
                                  --browser PATH            Set the path to the browser binary
                                  --crawl-delay MILLIS      Wait this long before crawling another page from the same queue.
                                  --include REGEX           Include pages that match the specified REGEX pattern in the crawl scope.
                                  -A, --user-agent STR      Set the User-Agent string to identify the crawler to the server.
                                  -w, --workers INT         Specify the number of browser and worker threads to use (default is %d).
                                
                                Examples:
                                  warcbot --include "https?://([^/]+\\.)example\\.com/.*" -A "MyCrawler/1.0" -w 5
                                """, config.getWorkers());
                        return;
                    }
                    case "--browser" -> config.setBrowserBinary(args[++i]);
                    case "--crawl-delay" -> config.setCrawlDelay(Integer.parseInt(args[++i]));
                    case "--include" -> config.addInclude(args[++i]);
                    case "--host" -> host = args[++i];
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--seed-file", "--seedFile" -> config.loadSeedFile(Path.of(args[++i]));
                    case "-A", "--user-agent", "--userAgent" -> config.setUserAgent(args[++i]);
                    case "-w", "--workers" -> config.setWorkers(Integer.parseInt(args[++i]));
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
            var httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
            httpServer.createContext("/", new Webapp(crawl));
            httpServer.setExecutor(Executors.newCachedThreadPool());
            httpServer.start();
        } else {
            crawl.start();
        }
    }
}
