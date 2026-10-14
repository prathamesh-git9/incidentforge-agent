package dev.incidentforge.domain;

import java.time.Instant;
import java.util.Set;

/**
 * One entry on the evidence timeline. The summary is pruned, redacted text derived from an
 * untrusted tool result; {@code toolCallId} and {@code contentSha256} tie it back to the exact
 * MCP call and raw payload it came from.
 */
public record EvidenceItem(
        String evidenceId,
        Kind kind,
        String sourceTool,
        String toolCallId,
        Instant observedAt,
        String contentSha256,
        String summary,
        Set<String> flags) {

    public static final String FLAG_REDACTED = "REDACTED";
    public static final String FLAG_PROMPT_INJECTION = "PROMPT_INJECTION_SUSPECTED";
    public static final String FLAG_TRUNCATED = "TRUNCATED";

    public enum Kind {
        METRIC, LOG_SIGNATURE, STACK_TRACE, TRACE, DEPLOY, FEATURE_FLAG, CONTEXT_STATS
    }

    public EvidenceItem {
        flags = flags == null ? Set.of() : Set.copyOf(flags);
    }

    public EvidenceItem withId(String id) {
        return new EvidenceItem(id, kind, sourceTool, toolCallId, observedAt, contentSha256, summary, flags);
    }
}
