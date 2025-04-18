package org.netpreserve.warcaroo;

public class CrawlLimitException extends Exception{
    public CrawlLimitException(String message) {
        super(message);
    }
}
