package dev.incidentforge.pipeline;

/** A stage failed for a reason that may go away (model overload, MCP timeout). Retried with a bound. */
public class TransientStageException extends RuntimeException {

    public TransientStageException(String message, Throwable cause) {
        super(message, cause);
    }
}
