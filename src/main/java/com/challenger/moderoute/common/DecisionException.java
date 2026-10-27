package com.challenger.moderoute.common;

public class DecisionException extends RuntimeException {
    public DecisionException(String message) {
        super(message);
    }

    public DecisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
