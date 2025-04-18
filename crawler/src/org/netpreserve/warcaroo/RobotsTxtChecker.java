package org.netpreserve.warcaroo;

import org.netpreserve.warcaroo.util.Url;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class RobotsTxtChecker {
    private static final Logger log = LoggerFactory.getLogger(RobotsTxtChecker.class);
    private final RobotsTxtDAO dao;
    private final HttpClient httpClient;
    private final Storage storage;
    private final List<String> userAgents;
    private final String fetchUserAgent;

    public RobotsTxtChecker(RobotsTxtDAO dao, HttpClient httpClient, Storage storage, List<String> userAgents,
                            String fetchUserAgent) {
        this.dao = dao;
        this.httpClient = httpClient;
        this.storage = storage;
        this.userAgents = userAgents;
        this.fetchUserAgent = fetchUserAgent;
    }

    boolean checkAllowed(long pageId, Url url) throws SQLException, IOException {
        Url robotsUrl = url.withPath("/robots.txt");
        var robots = dao.getRobotsTxt(robotsUrl.toString());
        if (robots == null || robots.lastChecked().isBefore(Instant.now().minus(Duration.ofDays(1)))) {
            URI robotsUri;
            try {
                robotsUri = robotsUrl.toURI();
            } catch (URISyntaxException e) {
                log.debug("Error parsing robots.txt URL: {}", robotsUrl, e);
                return true;
            }
            robots = fetch(pageId, robotsUri, robots);
        }
        return robots.allows(url, userAgents);
    }

    private RobotsTxt fetch(long pageId, URI robotsUri, RobotsTxt prev) throws IOException {
        var now = Instant.now();
        int status;
        byte[] body;
        try {
            var responseTimeRef = new AtomicReference<Instant>();
            long fetchStart = System.currentTimeMillis();
            var response = httpClient.send(HttpRequest.newBuilder(robotsUri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", fetchUserAgent)
                    .build(), responseInfo -> {
                responseTimeRef.set(Instant.now());
                return HttpResponse.BodySubscribers.ofByteArray();
            });
            long fetchTimeMs = System.currentTimeMillis() - fetchStart;
            status = response.statusCode();
            body = response.body();
            String ipAddress = InetAddress.getByName(robotsUri.getHost()).getHostAddress();
            storage.save(new Resource.Metadata(pageId, fetchTimeMs, ipAddress), response, responseTimeRef.get());
        } catch (InterruptedException e) {
            throw new IOException(e);
        } catch (IOException | IllegalArgumentException e) {
            status = -1;
            body = new byte[0];
        }

        if (status < 200 || status == 429 || status >= 500) {
            // server error, reuse stale value unless older than 30 days
            if (prev != null && prev.date().isAfter(now.minus(Period.ofDays(30)))) {
                dao.updateRobotsTxtLastChecked(robotsUri.toString(), now);
                return prev;
            }

            // no stale value available, so treat as not found
            body = new byte[0];
        } else if (status > 400) {
            // not found, treat as blank
            body = new byte[0];
        }

        dao.saveRobotsTxt(robotsUri.toString(), now, body);
        return new RobotsTxt(robotsUri.toString(), now, now, body);
    }
}
