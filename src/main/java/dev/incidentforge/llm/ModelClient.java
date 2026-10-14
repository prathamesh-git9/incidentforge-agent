package dev.incidentforge.llm;

import java.time.Duration;
import java.util.List;

/**
 * Provider-neutral model call. Implementations must not log request or response content.
 */
public interface ModelClient {

    String provider();

    Reply complete(Request request);

    enum Role { USER, ASSISTANT }

    /**
     * One conversation turn. {@code providerPayload} carries the provider's native assistant
     * message (e.g. including thinking blocks) so it can be replayed unmodified on the next turn.
     */
    record Turn(Role role, String text, Object providerPayload) {

        public static Turn user(String text) {
            return new Turn(Role.USER, text, null);
        }
    }

    record Request(String runId, String stage, String actor, String model, String effort, int maxOutputTokens,
                   Duration timeout, String system, List<Turn> turns) {
    }

    record Reply(String text, long inputTokens, long outputTokens, String stopReason, Object providerPayload) {
    }

    /** Rate limit, overload, 5xx or network failure: the stage may be retried within its retry budget. */
    class TransientModelException extends RuntimeException {
        public TransientModelException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Non-retryable failure (bad request, auth, refusal): the run stops for a human. */
    class ModelCallException extends RuntimeException {
        public ModelCallException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
