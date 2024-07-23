package org.netpreserve.warcbot;

import org.netpreserve.warcbot.robotstxt.RobotsParseHandler;
import org.netpreserve.warcbot.robotstxt.RobotsParser;
import org.netpreserve.warcbot.util.Url;

import java.time.Instant;
import java.util.List;

public record RobotsTxt(String url, Instant date, Instant lastChecked, byte[] body) {
    public boolean allows(Url url, List<String> userAgents) {
        var parser = new RobotsParser(new RobotsParseHandler());
        var matcher = parser.parse(body);
        return matcher.allowedByRobots(userAgents, url);
    }
}
