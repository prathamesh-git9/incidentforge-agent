package dev.incidentforge.pipeline.stages;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incidentforge.config.IncidentForgeProperties;
import dev.incidentforge.domain.EvidenceItem;
import dev.incidentforge.domain.EvidenceItem.Kind;
import dev.incidentforge.domain.IncidentReport;
import dev.incidentforge.domain.Stage;
import dev.incidentforge.evidence.InjectionDetector;
import dev.incidentforge.evidence.LogPruner;
import dev.incidentforge.evidence.Redactor;
import dev.incidentforge.observability.PipelineMetrics;
import dev.incidentforge.pipeline.StageHandler;
import dev.incidentforge.tools.ToolGateway;
import dev.incidentforge.tools.ToolGateway.CallContext;
import dev.incidentforge.tools.ToolGateway.ToolCallResult;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Deterministic context collector. It knows which telemetry to fetch for the incident window,
 * fetches it through the MCP tool gateway (paging through logs), and reduces megabytes of raw
 * data to a short evidence timeline — before any model is involved. Every evidence item keeps
 * the tool call id and the hash of the raw payload it came from.
 */
@Component
public class CollectContextStage implements StageHandler {

    private static final String ACTOR = "context-collector";

    private final ToolGateway tools;
    private final IncidentForgeProperties properties;
    private final ObjectMapper objectMapper;
    private final PipelineMetrics metrics;

    public CollectContextStage(ToolGateway tools, IncidentForgeProperties properties, ObjectMapper objectMapper,
                               PipelineMetrics metrics) {
        this.tools = tools;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Override
    public Stage stage() {
        return Stage.COLLECT_CONTEXT;
    }

    @Override
    public Outcome execute(Context context) {
        IncidentReport report = context.report();
        String service = context.run().service();
        Instant detected = detectedAt(report, context.run().createdAt());
        IncidentForgeProperties.ContextLimits limits = properties.context();
        CallContext call = new CallContext(context.run().id(), service, Stage.COLLECT_CONTEXT, ACTOR, null);
        Builder outcome = Outcome.from(context.run());
        List<EvidenceItem> items = new ArrayList<>();
        int toolCalls = 0;

        for (String metric : List.of("consumer_lag", "cpu_utilization")) {
            ToolCallResult result = tools.call(call, "metrics_query", Map.of("service", service, "metric", metric,
                    "from", detected.minus(limits.metricLookback()).toString(), "to", detected.toString()));
            toolCalls++;
            if (result.ok()) {
                analyzeMetric(metric, json(result.text()), result).ifPresent(items::add);
            }
        }

        Instant from = detected.minus(limits.logLookback());
        List<LogPruner.Line> lines = new ArrayList<>();
        Integer cursor = 0;
        int pages = 0;
        String lastLogCall = null;
        StringBuilder rawHashes = new StringBuilder();
        while (cursor != null && lines.size() < limits.maxLogLines()) {
            ToolCallResult page = tools.call(call, "logs_search", Map.of("service", service, "from", from.toString(),
                    "to", detected.toString(), "minLevel", "INFO", "cursor", cursor, "limit", limits.pageSize()));
            toolCalls++;
            pages++;
            if (!page.ok()) {
                outcome.note("WARN", "logs_search page " + pages + " failed: " + page.reason());
                break;
            }
            lastLogCall = page.callId();
            rawHashes.append(page.sha256());
            JsonNode body = json(page.text());
            for (JsonNode e : body.path("entries")) {
                lines.add(new LogPruner.Line(Instant.parse(e.path("timestamp").asText()), e.path("level").asText(),
                        e.path("logger").asText(), e.path("message").asText(),
                        e.hasNonNull("stack") ? e.path("stack").asText() : null));
            }
            cursor = body.path("nextCursor").isInt() ? body.path("nextCursor").asInt() : null;
        }
        LogPruner.Result pruned = LogPruner.prune(lines, limits.maxSignatures());
        String logsHash = dev.incidentforge.util.Hashing.sha256(rawHashes.toString());
        Set<String> stacksSeen = new HashSet<>();
        for (LogPruner.Signature sig : pruned.kept()) {
            items.add(item(Kind.LOG_SIGNATURE, "logs_search", lastLogCall, sig.firstSeen(), logsHash,
                    sig.level() + " " + sig.logger() + " x" + sig.count() + " between " + sig.firstSeen() + " and "
                            + sig.lastSeen() + ": " + abbreviate(sig.sample(), 400)));
            if (sig.stack() != null && stacksSeen.add(sig.stack())) {
                items.add(item(Kind.STACK_TRACE, "logs_search", lastLogCall, sig.firstSeen(), logsHash,
                        "first stack trace logged with '" + abbreviate(sig.fingerprint(), 80) + "':\n" + sig.stack()));
            }
        }
        long keptChars = items.stream().mapToLong(i -> i.summary().length()).sum();
        items.add(item(Kind.CONTEXT_STATS, "logs_search", lastLogCall, detected, logsHash,
                "pruned " + pruned.rawLines() + " log lines (" + pruned.rawBytes() / 1024 + " KiB) from " + pages
                        + " MCP pages into " + pruned.kept().size() + " of " + pruned.totalSignatures()
                        + " signatures"));
        metrics.contextBytes("raw", pruned.rawBytes());

        ToolCallResult traces = tools.call(call, "traces_search", Map.of("service", service, "from", from.toString(),
                "to", detected.toString(), "status", "ERROR", "limit", 50));
        toolCalls++;
        if (traces.ok()) {
            items.addAll(summarizeTraces(json(traces.text()), traces));
        }

        ToolCallResult deploys = tools.call(call, "deploys_list", Map.of("service", service,
                "since", detected.minus(Duration.ofDays(7)).toString()));
        toolCalls++;
        if (deploys.ok()) {
            for (JsonNode d : json(deploys.text()).path("deploys")) {
                List<String> files = new ArrayList<>();
                d.path("changedFiles").forEach(f -> files.add(f.asText()));
                items.add(item(Kind.DEPLOY, "deploys_list", deploys.callId(), Instant.parse(d.path("at").asText()),
                        deploys.sha256(), "deploy " + d.path("version").asText() + " commit " + d.path("commit").asText()
                                + ": " + d.path("summary").asText() + "\nchanged files: " + String.join(", ", files)));
            }
        }

        ToolCallResult flags = tools.call(call, "flags_list", Map.of("service", service));
        toolCalls++;
        if (flags.ok()) {
            for (JsonNode f : json(flags.text()).path("flags")) {
                Instant changed = Instant.parse(f.path("changedAt").asText());
                if (changed.isAfter(detected.minus(Duration.ofHours(24)))) {
                    items.add(item(Kind.FEATURE_FLAG, "flags_list", flags.callId(), changed, flags.sha256(),
                            "flag " + f.path("key").asText() + " enabled=" + f.path("enabled").asBoolean() + " rollout="
                                    + f.path("rolloutPercent").asInt() + "% changed at " + changed + ": "
                                    + f.path("description").asText()));
                }
            }
        }

        List<EvidenceItem> timeline = finalizeTimeline(items, outcome);
        metrics.contextBytes("pruned", timeline.stream().mapToLong(i -> i.summary().length()).sum());
        outcome.note("INFO", "collected " + timeline.size() + " evidence items via " + toolCalls
                + " MCP tool calls; " + pruned.rawLines() + " log lines (" + pruned.rawBytes() / 1024
                + " KiB) reduced to " + pruned.kept().size() + " signatures (" + keptChars + " chars)");
        return outcome.evidence(timeline).advance(Stage.CLASSIFY);
    }

    /** Redacts, flags suspected injections and assigns ids in chronological order (the evidence timeline). */
    private List<EvidenceItem> finalizeTimeline(List<EvidenceItem> items, Builder outcome) {
        List<EvidenceItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(EvidenceItem::observedAt).thenComparing(i -> i.kind().ordinal()));
        int budget = properties.context().maxEvidenceChars();
        List<EvidenceItem> out = new ArrayList<>();
        int used = 0;
        int n = 1;
        for (EvidenceItem item : sorted) {
            Redactor.Redacted redacted = Redactor.redact(item.summary());
            Set<String> flags = new HashSet<>(item.flags());
            String summary = redacted.text();
            if (redacted.changed()) {
                flags.add(EvidenceItem.FLAG_REDACTED);
            }
            if (InjectionDetector.suspicious(summary)) {
                flags.add(EvidenceItem.FLAG_PROMPT_INJECTION);
                metrics.injectionFlag();
            }
            if (used + summary.length() > budget) {
                summary = abbreviate(summary, Math.max(120, budget - used));
                flags.add(EvidenceItem.FLAG_TRUNCATED);
            }
            used += summary.length();
            EvidenceItem finalized = new EvidenceItem("E" + n++, item.kind(), item.sourceTool(), item.toolCallId(),
                    item.observedAt(), item.contentSha256(), summary, flags);
            if (flags.contains(EvidenceItem.FLAG_PROMPT_INJECTION)) {
                outcome.note("SECURITY", finalized.evidenceId() + " contains instruction-like text aimed at agents; "
                        + "kept as untrusted evidence only");
            }
            if (flags.contains(EvidenceItem.FLAG_REDACTED)) {
                outcome.note("SECURITY", finalized.evidenceId() + " had credentials or personal data redacted");
            }
            out.add(finalized);
        }
        return out;
    }

    private java.util.Optional<EvidenceItem> analyzeMetric(String metric, JsonNode body, ToolCallResult result) {
        record Analysis(String labels, double baseline, double peak, Instant onset) {
        }
        List<Analysis> anomalies = new ArrayList<>();
        int flat = 0;
        double flatBaseline = 0;
        for (JsonNode series : body.path("series")) {
            List<double[]> points = new ArrayList<>();
            List<Instant> times = new ArrayList<>();
            for (JsonNode p : series.path("points")) {
                times.add(Instant.parse(p.path("timestamp").asText()));
                points.add(new double[] {p.path("value").asDouble()});
            }
            if (points.size() < 6) {
                continue;
            }
            double[] values = points.stream().mapToDouble(v -> v[0]).toArray();
            double baseline = median(Arrays.copyOfRange(values, 0, values.length / 3));
            double peak = values[values.length - 1];
            if (baseline > 0 && peak > baseline * 2.5) {
                int onsetIndex = values.length - 1;
                while (onsetIndex > 0 && values[onsetIndex - 1] > baseline * 2) {
                    onsetIndex--;
                }
                Map<String, String> labels = new TreeMap<>();
                series.path("labels").fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
                anomalies.add(new Analysis(labels.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining(" ")), baseline, peak, times.get(onsetIndex)));
            } else {
                flat++;
                flatBaseline += baseline;
            }
        }
        if (anomalies.isEmpty()) {
            return java.util.Optional.empty();
        }
        Analysis top = anomalies.stream().max(Comparator.comparingDouble(a -> a.peak() / a.baseline())).orElseThrow();
        String summary = metric + " " + top.labels() + " rose from baseline ~" + format(top.baseline()) + " to "
                + format(top.peak()) + " (x" + Math.round(top.peak() / top.baseline()) + "); sustained since "
                + top.onset().truncatedTo(ChronoUnit.MINUTES) + "; " + (anomalies.size() - 1) + " other anomalous and "
                + flat + " flat series (flat baseline ~" + format(flat == 0 ? 0 : flatBaseline / flat) + ")";
        return java.util.Optional.of(item(Kind.METRIC, "metrics_query", result.callId(), top.onset(), result.sha256(),
                summary));
    }

    private List<EvidenceItem> summarizeTraces(JsonNode body, ToolCallResult result) {
        Map<String, List<JsonNode>> groups = new LinkedHashMap<>();
        for (JsonNode span : body.path("spans")) {
            JsonNode a = span.path("attributes");
            String key = a.path("exception.type").asText("?") + " partition=" + a.path("messaging.kafka.partition").asText("?")
                    + " offset=" + a.path("messaging.kafka.offset").asText("?");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(span);
        }
        List<EvidenceItem> out = new ArrayList<>();
        for (Map.Entry<String, List<JsonNode>> group : groups.entrySet()) {
            JsonNode firstSpan = group.getValue().getFirst();
            JsonNode lastSpan = group.getValue().getLast();
            out.add(item(Kind.TRACE, "traces_search", result.callId(), Instant.parse(firstSpan.path("start").asText()),
                    result.sha256(), body.path("totalMatched").asInt() + " ERROR spans matched (" + group.getValue().size()
                            + " sampled) for '" + firstSpan.path("name").asText() + "' with " + group.getKey()
                            + "; exception.message=" + firstSpan.path("attributes").path("exception.message").asText()
                            + "; retry.attempt up to " + lastSpan.path("attributes").path("retry.attempt").asText()
                            + "; example traceId=" + firstSpan.path("traceId").asText()));
        }
        return out;
    }

    private static EvidenceItem item(Kind kind, String tool, String callId, Instant observedAt, String sha, String summary) {
        return new EvidenceItem(null, kind, tool, callId, observedAt, sha == null ? "" : sha, summary, Set.of());
    }

    private JsonNode json(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode();
        }
    }

    private static Instant detectedAt(IncidentReport report, Instant fallback) {
        String value = report.metadata().get("detectedAt");
        try {
            return value == null ? fallback : Instant.parse(value);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted.length == 0 ? 0 : sorted[sorted.length / 2];
    }

    private static String format(double value) {
        return value >= 10 ? String.valueOf(Math.round(value)) : String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    static String abbreviate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 3) + "...";
    }
}
