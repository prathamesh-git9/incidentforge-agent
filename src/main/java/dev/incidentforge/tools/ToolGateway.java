package dev.incidentforge.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incidentforge.domain.Stage;
import dev.incidentforge.observability.PipelineMetrics;
import dev.incidentforge.store.AuditRepository;
import dev.incidentforge.util.Hashing;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every tool call — whether requested by deterministic code or by a model — goes through here:
 * authorize (stage policy and actor scope), schema-validate, bind to the run's service, execute
 * over MCP, and append an audit row. Denials are returned as data, never thrown past the caller,
 * so a model asking for a forbidden tool simply gets "denied" back and the attempt is recorded.
 */
@Component
public class ToolGateway {

    private static final Logger log = LoggerFactory.getLogger(ToolGateway.class);

    public enum Decision { ALLOWED, DENIED, ERROR }

    /** Who is calling: the run, its service, the pipeline stage and the actor (collector or investigator). */
    public record CallContext(String runId, String service, Stage stage, String actor, Set<String> actorTools) {
    }

    public record ToolCallResult(String callId, String tool, Decision decision, String text, String sha256,
                                 String reason) {
        public boolean ok() {
            return decision == Decision.ALLOWED;
        }
    }

    private final ToolCatalog catalog;
    private final SchemaValidator schemas;
    private final McpToolClient mcp;
    private final AuditRepository audit;
    private final PipelineMetrics metrics;
    private final ObservationRegistry observations;
    private final ObjectMapper objectMapper;

    public ToolGateway(ToolCatalog catalog, SchemaValidator schemas, McpToolClient mcp, AuditRepository audit,
                       PipelineMetrics metrics, ObservationRegistry observations, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.schemas = schemas;
        this.mcp = mcp;
        this.audit = audit;
        this.metrics = metrics;
        this.observations = observations;
        this.objectMapper = objectMapper;
    }

    public ToolCallResult call(CallContext ctx, String tool, Map<String, Object> requested) {
        Map<String, Object> arguments = requested == null ? Map.of() : requested;
        String callId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        String argsHash = Hashing.sha256(canonical(arguments));

        String denial = authorize(ctx, tool, arguments);
        if (denial != null) {
            metrics.policyDenial("tool");
            return finish(ctx, callId, tool, argsHash, Decision.DENIED, denial, null, started);
        }
        return Observation.createNotStarted("incidentforge.tool.call", observations)
                .lowCardinalityKeyValue("tool", tool)
                .lowCardinalityKeyValue("stage", ctx.stage().name())
                .observe(() -> {
                    try {
                        McpToolClient.RawResult raw = mcp.call(tool, arguments);
                        if (raw.isError()) {
                            return finish(ctx, callId, tool, argsHash, Decision.ERROR,
                                    "tool returned error: " + abbreviate(raw.text()), raw.text(), started);
                        }
                        return finish(ctx, callId, tool, argsHash, Decision.ALLOWED, null, raw.text(), started);
                    } catch (RuntimeException e) {
                        throw new ToolTransportException("MCP call failed for tool " + tool, e);
                    }
                });
    }

    private String authorize(CallContext ctx, String tool, Map<String, Object> arguments) {
        if (tool == null || catalog.find(tool).isEmpty()) {
            return "unknown tool '" + abbreviate(tool) + "' (no such capability exists)";
        }
        if (!ToolPolicy.allowedFor(ctx.stage()).contains(tool)) {
            return "tool '" + tool + "' is not permitted in stage " + ctx.stage();
        }
        if (ctx.actorTools() != null && !ctx.actorTools().contains(tool)) {
            return "tool '" + tool + "' is outside the scope of actor " + ctx.actor();
        }
        SchemaValidator.Result valid = schemas.validate(catalog.find(tool).orElseThrow().inputSchema(), arguments);
        if (!valid.valid()) {
            return "arguments rejected by schema: " + abbreviate(valid.error());
        }
        Object service = arguments.get("service");
        if (!ctx.service().equals(service)) {
            return "service must be the run's service '" + ctx.service() + "'";
        }
        return null;
    }

    private ToolCallResult finish(CallContext ctx, String callId, String tool, String argsHash, Decision decision,
                                  String reason, String text, long started) {
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        String resultHash = text == null ? null : Hashing.sha256(text);
        audit.recordToolCall(new AuditRepository.ToolCallRecord(callId, ctx.runId(), ctx.stage().name(), ctx.actor(),
                tool == null ? "(null)" : tool, argsHash, decision.name(), reason, latencyMs, resultHash,
                text == null ? 0 : text.length(), Instant.now()));
        // Unknown names are model-chosen text: never use them as a metric tag or log them verbatim.
        String knownTool = tool != null && catalog.find(tool).isPresent() ? tool : "unknown";
        metrics.toolCall(knownTool, decision.name().toLowerCase());
        if (decision == Decision.DENIED) {
            log.warn("tool call denied tool={} actor={} callId={}", knownTool, ctx.actor(), callId);
        }
        return new ToolCallResult(callId, tool, decision, text, resultHash, reason);
    }

    private String canonical(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(arguments));
        } catch (JsonProcessingException e) {
            return String.valueOf(arguments);
        }
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= 160 ? value : value.substring(0, 157) + "...";
    }

    /** Transport failure talking to the MCP server: transient, eligible for bounded retry. */
    public static class ToolTransportException extends RuntimeException {
        public ToolTransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
