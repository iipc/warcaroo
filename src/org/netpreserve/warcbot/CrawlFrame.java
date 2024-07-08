package org.netpreserve.warcbot;

import javax.swing.*;

public class CrawlFrame extends JFrame {
    private final Crawl crawl;

    public CrawlFrame(Crawl crawl) {
        super("Crawl");
        this.crawl = crawl;
    }

    public static void main(String[] args) {
        var frame = new CrawlFrame(new CrawlClient("http://localhost:8080"));
        frame.setVisible(true);
    }
}
