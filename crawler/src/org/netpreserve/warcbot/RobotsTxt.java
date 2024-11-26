package org.netpreserve.warcaroo;

import org.netpreserve.warcaroo.robotstxt.RobotsParseHandler;
import org.netpreserve.warcaroo.robotstxt.RobotsParser;
import org.netpreserve.warcaroo.util.Url;

import java.time.Instant;
import java.util.List;

public record RobotsTxt(String url, Instant date, Instant lastChecked, byte[] body) {
    public boolean allows(Url url, List<String> userAgents) {
        var parser = new RobotsParser(new RobotsParseHandler());
        var matcher = parser.parse(body);
        return matcher.allowedByRobots(userAgents, url);
    }
}
