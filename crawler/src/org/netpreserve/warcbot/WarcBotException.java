package org.netpreserve.warcaroo;

public abstract class WarcarooException extends Exception {
    public WarcarooException(String message) {
        super(message);
    }

    public abstract int status();
}
