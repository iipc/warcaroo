package org.netpreserve.warcbot;

import org.netpreserve.warcbot.util.Url;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.util.List;
import java.util.UUID;

public class RobotsTxtChecker {
    private final RobotsTxtDAO dao;
    private final HttpClient httpClient;
    private final Storage storage;
    private final List<String> userAgents;
    private final String userAgent;

    public RobotsTxtChecker(RobotsTxtDAO dao, HttpClient httpClient, Storage storage, List<String> userAgents,
                            String userAgent) {
        this.dao = dao;
        this.httpClient = httpClient;
        this.storage = storage;
        this.userAgents = userAgents;
        this.userAgent = userAgent;
    }

    boolean checkAllowed(UUID pageId, Url url) throws SQLException, IOException {
        Url robotsUrl = url.withPath("/robots.txt");
        var robots = dao.getRobotsTxt(robotsUrl.toString());
        if (robots == null || robots.lastChecked().isBefore(Instant.now().minus(Duration.ofDays(1)))) {
            robots = fetch(pageId, robotsUrl.toURI(), robots);
        }
        return robots.allows(url, userAgents);
    }

    private RobotsTxt fetch(UUID pageId, URI robotsUri, RobotsTxt prev) throws IOException {
        var now = Instant.now();
        int status;
        byte[] body;
        try {
            long fetchStart = System.currentTimeMillis();
            var response = httpClient.send(HttpRequest.newBuilder(robotsUri)
                    .header("User-Agent", userAgent)
                    .build(), BodyHandlers.ofByteArray());
            long fetchTimeMs = System.currentTimeMillis() - fetchStart;
            status = response.statusCode();
            body = response.body();
            String ipAddress = response.sslSession().map(SSLSession::getPeerHost).orElse(null);
            storage.save(new Resource.Metadata(pageId, fetchTimeMs, ipAddress), response);
        } catch (InterruptedException e) {
            throw new IOException(e);
        } catch (IOException e) {
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
