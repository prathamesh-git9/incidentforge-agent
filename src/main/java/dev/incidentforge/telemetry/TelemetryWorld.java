package dev.incidentforge.telemetry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Read-only telemetry for one synthetic service: logs, metrics, spans, deploys and feature flags.
 * Each seeded incident fixture is one implementation; the MCP tools serve whichever world owns
 * the requested service.
 */
public interface TelemetryWorld {

    String service();

    List<LogEntry> logs();

    List<Series> metric(String name);

    List<Span> spans();

    List<Deploy> deploys();

    List<Flag> flags();

    record LogEntry(Instant timestamp, String level, String logger, String message, String stack) {
    }

    record MetricPoint(Instant timestamp, double value) {
    }

    record Series(Map<String, String> labels, List<MetricPoint> points) {
    }

    record Span(String traceId, String name, Instant start, long durationMs, String status,
                Map<String, String> attributes) {
    }

    record Deploy(String version, Instant at, String commit, String summary, List<String> changedFiles) {
    }

    /** {@code codeRefs}: source files that read the flag, as recorded by the flag service. */
    record Flag(String key, boolean enabled, int rolloutPercent, Instant changedAt, String description,
                List<String> codeRefs) {
    }

    static String traceId(java.util.Random random) {
        return String.format("%016x%016x", random.nextLong(), random.nextLong());
    }
}
