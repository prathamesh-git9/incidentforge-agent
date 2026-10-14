package dev.incidentforge.pipeline;

/** A command that can never succeed (malformed, unknown run). Dead-lettered without retries. */
public class PoisonCommandException extends RuntimeException {

    public PoisonCommandException(String message, Throwable cause) {
        super(message, cause);
    }
}
