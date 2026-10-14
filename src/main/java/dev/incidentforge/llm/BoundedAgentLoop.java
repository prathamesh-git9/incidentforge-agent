package dev.incidentforge.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incidentforge.config.IncidentForgeProperties.StageBudget;
import dev.incidentforge.domain.Stage;
import dev.incidentforge.evidence.InjectionDetector;
import dev.incidentforge.evidence.Redactor;
import dev.incidentforge.observability.PipelineMetrics;
import dev.incidentforge.store.AuditRepository;
import dev.incidentforge.tools.SchemaValidator;
import dev.incidentforge.tools.ToolGateway;
import dev.incidentforge.util.Hashing;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Runs one model actor for one stage under hard limits: a maximum number of turns, a maximum
 * input size, at most N tool calls per turn, and only the tools in the actor's scope. Every model
 * reply is parsed and schema-validated in code; invalid replies get one corrective message per
 * turn, and the loop ends with an explicit outcome instead of running until something works.
 */
@Component
public class BoundedAgentLoop {

    public enum Status { COMPLETED, BUDGET_EXHAUSTED, INVALID_OUTPUT }

    public record Spec(String runId, String service, Stage stage, String actor, StageBudget budget, String system,
                       String boundary, String initialUserMessage, Set<String> tools, String resultSchema,
                       int maxToolCallsPerTurn, int maxToolResultChars) {
    }

    public record Outcome(Status status, JsonNode result, int turns, List<ToolGateway.ToolCallResult> toolCalls,
                          int deniedToolCalls, int injectionFlags, String detail) {
    }

    private final ModelClient model;
    private final ToolGateway tools;
    private final SchemaValidator schemas;
    private final AuditRepository audit;
    private final PipelineMetrics metrics;
    private final ObservationRegistry observations;
    private final ObjectMapper objectMapper;

    public BoundedAgentLoop(ModelClient model, ToolGateway tools, SchemaValidator schemas, AuditRepository audit,
                            PipelineMetrics metrics, ObservationRegistry observations, ObjectMapper objectMapper) {
        this.model = model;
        this.tools = tools;
        this.schemas = schemas;
        this.audit = audit;
        this.metrics = metrics;
        this.observations = observations;
        this.objectMapper = objectMapper;
    }

    public Outcome run(Spec spec) {
        List<ModelClient.Turn> turns = new ArrayList<>();
        turns.add(ModelClient.Turn.user(spec.initialUserMessage()));
        List<ToolGateway.ToolCallResult> calls = new ArrayList<>();
        int denied = 0;
        int injectionFlags = 0;
        String lastProblem = "no reply";
        for (int turn = 1; turn <= spec.budget().maxTurns(); turn++) {
            int inputChars = spec.system().length() + turns.stream().mapToInt(t -> t.text().length()).sum();
            if (inputChars > spec.budget().maxInputChars()) {
                return finish(spec, Status.BUDGET_EXHAUSTED, null, turn - 1, calls, denied, injectionFlags,
                        "input budget of " + spec.budget().maxInputChars() + " chars exceeded");
            }
            ModelClient.Reply reply = callModel(spec, turns, turn, inputChars);
            turns.add(new ModelClient.Turn(ModelClient.Role.ASSISTANT, reply.text(), reply.providerPayload()));

            JsonNode envelope = parseJson(reply.text());
            if (envelope == null) {
                lastProblem = "reply was not a JSON object";
                turns.add(ModelClient.Turn.user("Your reply was not a single JSON object. Reply with JSON only."));
                continue;
            }
            SchemaValidator.Result envelopeCheck = schemas.validateModelOutput("envelope", envelope);
            if (!envelopeCheck.valid()) {
                lastProblem = "envelope invalid";
                turns.add(ModelClient.Turn.user("Your reply did not match the envelope: " + envelopeCheck.error()));
                continue;
            }
            if ("final".equals(envelope.path("action").asText())) {
                JsonNode result = envelope.get("result");
                SchemaValidator.Result resultCheck = result == null
                        ? new SchemaValidator.Result(false, "missing result")
                        : schemas.validateModelOutput(spec.resultSchema(), result);
                if (resultCheck.valid()) {
                    return finish(spec, Status.COMPLETED, result, turn, calls, denied, injectionFlags, null);
                }
                lastProblem = "result failed schema " + spec.resultSchema();
                turns.add(ModelClient.Turn.user("Your result did not match the result schema: " + resultCheck.error()));
                continue;
            }
            StringBuilder results = new StringBuilder();
            int executed = 0;
            for (JsonNode call : envelope.path("calls")) {
                if (executed++ >= spec.maxToolCallsPerTurn()) {
                    results.append("[tool_result status=SKIPPED] per-turn tool call limit reached\n");
                    continue;
                }
                String tool = call.path("tool").asText();
                Map<String, Object> arguments = objectMapper.convertValue(call.path("arguments"),
                        new TypeReference<Map<String, Object>>() {
                        });
                ToolGateway.ToolCallResult result = tools.call(new ToolGateway.CallContext(spec.runId(), spec.service(),
                        spec.stage(), spec.actor(), spec.tools()), tool, arguments);
                calls.add(result);
                if (result.decision() == ToolGateway.Decision.DENIED) {
                    denied++;
                    results.append("[tool_result call=").append(result.callId()).append(" status=DENIED] ")
                            .append(result.reason()).append('\n');
                    continue;
                }
                String text = result.text() == null ? "" : result.text();
                if (text.length() > spec.maxToolResultChars()) {
                    text = text.substring(0, spec.maxToolResultChars()) + "\n[truncated by context budget]";
                }
                Redactor.Redacted redacted = Redactor.redact(text);
                boolean suspicious = InjectionDetector.suspicious(redacted.text());
                if (suspicious) {
                    injectionFlags++;
                    metrics.injectionFlag();
                }
                results.append("[tool_result call=").append(result.callId()).append(" tool=").append(tool)
                        .append(" status=").append(result.decision())
                        .append(suspicious ? " flags=PROMPT_INJECTION_SUSPECTED" : "").append("]\n")
                        .append(PromptBuilder.fence(spec.boundary(), redacted.text())).append('\n');
            }
            turns.add(ModelClient.Turn.user(results.toString()));
            lastProblem = "turn budget exhausted while still calling tools";
        }
        return finish(spec, Status.BUDGET_EXHAUSTED, null, spec.budget().maxTurns(), calls, denied, injectionFlags,
                "stopped after " + spec.budget().maxTurns() + " turns: " + lastProblem);
    }

    private ModelClient.Reply callModel(Spec spec, List<ModelClient.Turn> turns, int turn, int inputChars) {
        StageBudget budget = spec.budget();
        ModelClient.Request request = new ModelClient.Request(spec.runId(), spec.stage().name(), spec.actor(),
                budget.model(), budget.effort(), budget.maxOutputTokens(), budget.timeout(), spec.system(),
                List.copyOf(turns));
        String promptHash = Hashing.sha256(spec.system() + turns.stream().map(ModelClient.Turn::text).toList());
        long started = System.nanoTime();
        String outcome = "ok";
        ModelClient.Reply reply = null;
        try {
            reply = Observation.createNotStarted("incidentforge.model.call", observations)
                    .lowCardinalityKeyValue("provider", model.provider())
                    .lowCardinalityKeyValue("stage", spec.stage().name())
                    .lowCardinalityKeyValue("model", budget.model())
                    .observe(() -> model.complete(request));
            return reply;
        } catch (ModelClient.TransientModelException e) {
            outcome = "transient_error";
            throw e;
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            long in = reply == null ? 0 : reply.inputTokens();
            long out = reply == null ? 0 : reply.outputTokens();
            audit.recordModelCall(new AuditRepository.ModelCallRecord(spec.runId(), spec.stage().name(), spec.actor(),
                    model.provider(), budget.model(), turn, promptHash, inputChars,
                    reply == null ? 0 : reply.text().length(), in, out, outcome, latencyMs, Instant.now()));
            metrics.modelTokens(spec.stage().name(), "input", in);
            metrics.modelTokens(spec.stage().name(), "output", out);
        }
    }

    private Outcome finish(Spec spec, Status status, JsonNode result, int turns, List<ToolGateway.ToolCallResult> calls,
                           int denied, int injectionFlags, String detail) {
        metrics.modelTurns(spec.stage().name(), turns);
        return new Outcome(status, result, turns, List.copyOf(calls), denied, injectionFlags, detail);
    }

    /** Extracts the outermost JSON object from a reply (tolerating code fences or stray prose). */
    JsonNode parseJson(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(text.substring(start, end + 1));
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
