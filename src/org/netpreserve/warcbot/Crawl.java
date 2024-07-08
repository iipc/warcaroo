package org.netpreserve.warcbot;

import java.util.List;

public interface Crawl {
    Config getConfig();
    void setConfig(Config config);
    void pause();
    void unpause();
    List<Candidate> listQueue(String queue);
}
