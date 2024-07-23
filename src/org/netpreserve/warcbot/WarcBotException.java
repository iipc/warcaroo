package org.netpreserve.warcbot;

public abstract class WarcBotException extends Exception {
    public WarcBotException(String message) {
        super(message);
    }

    public abstract int status();
}
