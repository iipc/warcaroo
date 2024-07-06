package org.netpreserve.warcbot;

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
    private final Database db;
    private final HttpClient httpClient;
    private final Storage storage;
    private final List<String> userAgents;

    public RobotsTxtChecker(Database db, HttpClient httpClient, Storage storage, List<String> userAgents) {
        this.db = db;
        this.httpClient = httpClient;
        this.storage = storage;
        this.userAgents = userAgents;
    }

    boolean checkAllowed(UUID pageId, Url url) throws SQLException, IOException {
        URI robotsUri = url.toURI().resolve("/robots.txt");
        var robots = db.robotsGet(robotsUri.toString());
        if (robots == null || robots.lastChecked().isBefore(Instant.now().minus(Duration.ofDays(1)))) {
            robots = fetch(pageId, robotsUri, robots);
        }
        return robots.allows(url, userAgents);
    }

    private RobotsTxt fetch(UUID pageId, URI robotsUri, RobotsTxt prev) throws IOException, SQLException {
        var now = Instant.now();
        int status;
        byte[] body;
        try {
            var response = httpClient.send(HttpRequest.newBuilder(robotsUri).build(), BodyHandlers.ofByteArray());
            status = response.statusCode();
            body = response.body();
            storage.save(pageId, response);
        } catch (InterruptedException e) {
            throw new IOException(e);
        } catch (IOException e) {
            status = -1;
            body = new byte[0];
        }

        if (status < 200 || status == 429 || status >= 500) {
            // server error, reuse stale value unless older than 30 days
            if (prev != null && prev.date().isAfter(now.minus(Period.ofDays(30)))) {
                db.robotsUpdateLastChecked(robotsUri.toString(), now);
                return prev;
            }

            // no stale value available, so treat as not found
            body = new byte[0];
        } else if (status > 400) {
            // not found, treat as blank
            body = new byte[0];
        }

        db.robotsUpsert(robotsUri.toString(), now, body);
        return new RobotsTxt(robotsUri.toString(), now, now, body);
    }
}
