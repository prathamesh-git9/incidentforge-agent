package dev.incidentforge.domain;

import java.util.Map;

/**
 * The sparse report a human or alerting bot files: one sentence plus a little metadata.
 * Everything else is gathered by the deterministic context collector.
 */
public record IncidentReport(String service, String summary, String reportedBy, String environment,
                             Map<String, String> metadata) {

    public IncidentReport {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
