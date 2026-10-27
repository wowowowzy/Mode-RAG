package com.challenger.moderoute.common;

public class RecallException extends RuntimeException {
    public RecallException(String message) {
        super(message);
    }

    public RecallException(String message, Throwable cause) {
        super(message, cause);
    }
}
