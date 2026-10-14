package dev.incidentforge.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality pipeline metrics (no run ids, prompts or payloads as tags).
 * Exposed at /actuator/prometheus.
 */
@Component
public class PipelineMetrics {

    private final MeterRegistry registry;

    public PipelineMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void command(String outcome) {
        registry.counter("incidentforge.commands", "outcome", outcome).increment();
    }

    public void stage(String stage, String outcome, Duration duration) {
        Timer.builder("incidentforge.stage.duration").tag("stage", stage).tag("outcome", outcome)
                .register(registry).record(duration);
    }

    public void toolCall(String tool, String decision) {
        registry.counter("incidentforge.tool.calls", "tool", tool, "decision", decision).increment();
    }

    public void policyDenial(String type) {
        registry.counter("incidentforge.policy.denials", "type", type).increment();
    }

    public void injectionFlag() {
        registry.counter("incidentforge.evidence.injection_flags").increment();
    }

    public void contextBytes(String phase, long bytes) {
        registry.summary("incidentforge.context.bytes", "phase", phase).record(bytes);
    }

    public void modelTokens(String stage, String direction, long tokens) {
        registry.counter("incidentforge.model.tokens", "stage", stage, "direction", direction).increment(tokens);
    }

    public void modelTurns(String stage, int turns) {
        registry.summary("incidentforge.model.turns", "stage", stage).record(turns);
    }

    public void publish(String outcome) {
        registry.counter("incidentforge.publish", "outcome", outcome).increment();
    }
}
