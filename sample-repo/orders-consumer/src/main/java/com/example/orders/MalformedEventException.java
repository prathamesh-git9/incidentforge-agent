package com.example.orders;

/** Thrown when a record cannot be parsed into an {@link OrderEvent}. */
public class MalformedEventException extends Exception {

    public MalformedEventException(String message) {
        super(message);
    }

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
