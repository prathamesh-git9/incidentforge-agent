package dev.incidentforge.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, credential-free stand-in for a model. It is a SCRIPTED heuristic that reads
 * the same prompts a real model receives and answers in the same JSON envelope, including
 * multi-turn tool use. It exists so the whole pipeline (MCP tools, budgets, validation, policy,
 * approval, publishing) can be run and tested offline. It demonstrates the plumbing and the
 * guardrails, not model reasoning quality.
 *
 * <p>Classification, timeline and change correlation are generic rules over the evidence and work
 * the same for every fixture. Locating the suspect line and choosing the edit are per-fixture
 * scripts in {@link OfflineScenarios}.
 */
public class OfflineModelClient implements ModelClient {

    private static final Pattern EVIDENCE = Pattern.compile(
            "\\[(E\\d+)] kind=(\\w+) source=(\\S+) observed=(\\S+) flags=(\\S*)\\n<<(data-[0-9a-f]+)\\n(.*?)\\n\\6>>",
            Pattern.DOTALL);
    private static final Pattern FENCED = Pattern.compile("<<(data-[0-9a-f]+)\\n(.*?)\\n\\1>>", Pattern.DOTALL);
    private static final Pattern SERVICE = Pattern.compile("service=([a-z][a-z0-9-]+)");
    private static final Pattern COUNT = Pattern.compile(" x(\\d+) ");
    private static final Pattern BETWEEN = Pattern.compile(" between (\\S+) and (\\S+):");
    private static final Pattern SUSPECT = Pattern.compile("Suspect location: (\\S+?):(\\d+)");
    private static final Pattern JAVA_FILE = Pattern.compile("([A-Z][A-Za-z0-9]+\\.java)");
    private static final Pattern TRACE_FILE = Pattern.compile("code\\.filepath=(\\S+?\\.java)");
    private static final Pattern TRACE_LINE = Pattern.compile("code\\.lineno=(\\d+)");

    private record Evidence(String id, String kind, Instant observed, String text) {

        boolean anomalousMetric() {
            return kind.equals("METRIC") && text.contains(" rose from baseline");
        }

        boolean stableMetric() {
            return kind.equals("METRIC") && text.contains("no sustained anomaly");
        }

        boolean problemSignature() {
            return kind.equals("LOG_SIGNATURE") && (text.startsWith("WARN") || text.startsWith("ERROR"));
        }
    }

    private final ObjectMapper objectMapper;

    public OfflineModelClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String provider() {
        return "offline";
    }

    @Override
    public Reply complete(Request request) {
        String first = request.turns().getFirst().text();
        List<String> toolResults = request.turns().stream()
                .skip(1)
                .filter(t -> t.role() == Role.USER && t.text().startsWith("[tool_result"))
                .map(Turn::text)
                .toList();
        String service = match(SERVICE, first, 1).orElse("unknown");
        Optional<OfflineScenarios.Scenario> scenario = OfflineScenarios.forService(service);
        ObjectNode reply = switch (request.actor()) {
            case "classifier" -> classify(evidence(first));
            case "timeline-investigator" -> timeline(evidence(first));
            case "change-investigator" -> change(evidence(first));
            case "code-path-investigator" -> scenario
                    .map(s -> codePath(s, service, evidence(first), toolResults))
                    .orElseGet(() -> fin(lowConfidenceFinding(evidence(first))));
            case "fixer" -> scenario.map(s -> fix(s, service, first, toolResults))
                    .orElseGet(() -> fin(objectMapper.createObjectNode()));
            default -> fin(objectMapper.createObjectNode());
        };
        String text = reply.toString();
        return new Reply(text, first.length() / 4, text.length() / 4, "end_turn", null);
    }

    // ---- generic, evidence-driven actors ----------------------------------------------------------

    private ObjectNode classify(List<Evidence> evidence) {
        Optional<Evidence> appCode = evidence.stream()
                .filter(e -> (e.kind().equals("STACK_TRACE") && e.text().contains("com.example"))
                        || (e.kind().equals("TRACE") && e.text().contains("code.filepath=src/main/java/")))
                .findFirst();
        Optional<Evidence> symptom = evidence.stream().filter(Evidence::anomalousMetric).findFirst();
        Optional<Evidence> hot = hottest(evidence, false);
        ObjectNode result = objectMapper.createObjectNode();
        if (appCode.isPresent() && symptom.isPresent()) {
            result.put("category", "APPLICATION_CODE").put("codeFixRecommended", true).put("confidence", 0.8)
                    .put("rationale", "The symptom metric is anomalous for a narrow slice of traffic and the failing "
                            + "stack frames or slow spans point into the service's own source tree.");
            Set<String> ids = new LinkedHashSet<>();
            hot.ifPresent(h -> ids.add(h.id()));
            ids.add(appCode.get().id());
            ids.add(symptom.get().id());
            ids(result, ids);
        } else {
            result.put("category", "UNKNOWN").put("codeFixRecommended", false).put("confidence", 0.3)
                    .put("rationale", "No stack trace or span in the service's own code was collected.");
            ids(result, Set.of(evidence.isEmpty() ? "E1" : evidence.getFirst().id()));
        }
        return fin(result);
    }

    private ObjectNode timeline(List<Evidence> evidence) {
        Optional<Instant> onset = onset(evidence);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode claims = result.putArray("claims");
        List<Evidence> symptoms = evidence.stream().filter(Evidence::anomalousMetric).toList();
        for (Evidence metric : symptoms) {
            claim(claims, "Symptom: " + metric.text().split(";")[0] + ".", metric.id());
        }
        Optional<Evidence> hot = hottest(evidence, false);
        hot.ifPresent(h -> claim(claims, "Most frequent warning/error: " + abbreviate(message(h.text()), 200), h.id()));
        for (Evidence stable : evidence.stream().filter(Evidence::stableMetric).toList()) {
            claim(claims, stable.text().split(" ")[0] + " moved but did not stay anomalous while the symptom persisted, "
                    + "so it does not explain the incident.", stable.id());
        }
        if (onset.isPresent()) {
            for (Evidence sig : evidence.stream().filter(Evidence::problemSignature).toList()) {
                Matcher m = BETWEEN.matcher(sig.text());
                if (!m.find() || hot.map(h -> h.id().equals(sig.id())).orElse(false)) {
                    continue;
                }
                Instant firstSeen = Instant.parse(m.group(1));
                Instant lastSeen = Instant.parse(m.group(2));
                String logger = sig.text().split(" ")[1];
                if (lastSeen.isBefore(onset.get())) {
                    claim(claims, "'" + logger + "' warnings stop before the onset; unrelated.", sig.id());
                } else if (firstSeen.isBefore(onset.get().minus(Duration.ofMinutes(2)))) {
                    claim(claims, "'" + logger + "' warnings occur at a similar rate before and after the onset; "
                            + "not correlated with it.", sig.id());
                }
            }
        }
        String hypothesis = symptoms.isEmpty() ? "No sustained anomaly was found in the collected metrics."
                : "Onset " + onset.map(Instant::toString).orElse("unknown") + ": " + symptoms.getFirst().text().split(";")[0]
                + hot.map(h -> "; co-occurring signal: " + abbreviate(message(h.text()), 120)).orElse("");
        result.put("hypothesis", hypothesis);
        if (claims.isEmpty()) {
            claim(claims, "No anomalous signal in the evidence slice.", evidence.isEmpty() ? "E1" : evidence.getFirst().id());
        }
        result.put("confidence", symptoms.isEmpty() ? 0.2 : 0.7);
        return fin(result);
    }

    /** Picks the latest deploy or flag change before the onset that touches code in the failing evidence. */
    private ObjectNode change(List<Evidence> evidence) {
        Optional<Instant> onset = onset(evidence);
        List<Evidence> failing = evidence.stream()
                .filter(e -> e.kind().equals("STACK_TRACE") || e.kind().equals("TRACE")).toList();
        Set<String> failingFiles = new LinkedHashSet<>();
        for (Evidence e : failing) {
            Matcher m = JAVA_FILE.matcher(e.text());
            while (m.find()) {
                failingFiles.add(m.group(1));
            }
        }
        List<Evidence> changes = evidence.stream()
                .filter(e -> e.kind().equals("DEPLOY") || e.kind().equals("FEATURE_FLAG")).toList();
        Optional<Evidence> chosen = changes.stream()
                .filter(c -> onset.map(o -> !c.observed().isAfter(o)).orElse(true))
                .filter(c -> !touched(c, failingFiles).isEmpty())
                .max(Comparator.comparing(Evidence::observed));
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode claims = result.putArray("claims");
        chosen.ifPresent(c -> claim(claims, (c.kind().equals("DEPLOY") ? "The deploy" : "The flag change")
                + " '" + firstLine(c.text()) + "' happened "
                + onset.map(o -> Duration.between(c.observed(), o).toMinutes() + " min ").orElse("")
                + "before the onset and touches " + touched(c, failingFiles) + ", which appear in the failing evidence.",
                c.id(), failing.isEmpty() ? c.id() : failing.getFirst().id()));
        for (Evidence other : changes) {
            if (chosen.map(c -> c.id().equals(other.id())).orElse(false)) {
                continue;
            }
            claim(claims, "Ruled out '" + abbreviate(firstLine(other.text()), 120) + "': "
                    + (touched(other, failingFiles).isEmpty() ? "it does not touch code in the failing evidence."
                    : "it happened after the onset."), other.id());
        }
        result.put("hypothesis", chosen.map(c -> firstLine(c.text()))
                .orElse("No deploy or flag change correlates with the failing code path."));
        if (claims.isEmpty()) {
            claim(claims, "No changes were found in the collection window.",
                    evidence.isEmpty() ? "E1" : evidence.getFirst().id());
        }
        result.put("confidence", chosen.isPresent() ? 0.75 : 0.2);
        return fin(result);
    }

    // ---- per-fixture scripts ----------------------------------------------------------------------

    private ObjectNode codePath(OfflineScenarios.Scenario scenario, String service, List<Evidence> evidence,
                                List<String> toolResults) {
        if (toolResults.isEmpty()) {
            if (scenario.locate() == OfflineScenarios.Locate.LOG_PHRASE) {
                String phrase = hottest(evidence, true)
                        .map(e -> e.text().substring(e.text().lastIndexOf(';') + 1).strip()).orElse("Exception");
                return calls(call("code_search", objectMapper.createObjectNode().put("service", service)
                        .put("query", phrase.length() > 100 ? phrase.substring(0, 100) : phrase)));
            }
            Optional<Evidence> trace = evidence.stream()
                    .filter(e -> e.kind().equals("TRACE") && TRACE_FILE.matcher(e.text()).find()).findFirst();
            if (trace.isEmpty()) {
                return fin(lowConfidenceFinding(evidence));
            }
            int line = match(TRACE_LINE, trace.get().text(), 1).map(Integer::parseInt).orElse(1);
            return calls(call("code_read", objectMapper.createObjectNode().put("service", service)
                    .put("path", match(TRACE_FILE, trace.get().text(), 1).orElseThrow())
                    .put("startLine", Math.max(1, line - 12)).put("endLine", line + 12)));
        }
        JsonNode lastResult = fencedJson(toolResults.getLast());
        if (scenario.locate() == OfflineScenarios.Locate.LOG_PHRASE && toolResults.size() == 1) {
            JsonNode match = lastResult == null ? null : lastResult.path("matches").path(0);
            if (match == null || match.isMissingNode()) {
                return fin(lowConfidenceFinding(evidence));
            }
            int line = match.path("line").asInt();
            return calls(call("code_read", objectMapper.createObjectNode().put("service", service)
                    .put("path", match.path("path").asText()).put("startLine", Math.max(1, line - 16))
                    .put("endLine", line + 8)));
        }
        if (lastResult == null || !lastResult.has("content")) {
            return fin(lowConfidenceFinding(evidence));
        }
        String[] lines = lastResult.path("content").asText().split("\n", -1);
        int startLine = lastResult.path("startLine").asInt(1);
        int suspectLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(scenario.suspectMarker())) {
                suspectLine = startLine + i;
            }
        }
        if (suspectLine < 0) {
            return fin(lowConfidenceFinding(evidence));
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("hypothesis", scenario.hypothesis());
        result.put("suspectPath", lastResult.path("path").asText()).put("suspectLine", suspectLine)
                .put("suspectSymbol", scenario.suspectSymbol());
        ArrayNode claims = result.putArray("claims");
        Optional<Evidence> codeEvidence = evidence.stream()
                .filter(e -> e.kind().equals("STACK_TRACE") || e.kind().equals("TRACE")).findFirst();
        Optional<Evidence> hot = hottest(evidence, false);
        codeEvidence.ifPresent(e -> claim(claims, scenario.codeClaim().formatted(Integer.valueOf(lastLine(lines,
                startLine, scenario.suspectMarker()))), e.id()));
        hot.ifPresent(h -> claim(claims, "Co-occurring signal: " + abbreviate(message(h.text()), 200), h.id()));
        evidence.stream().filter(e -> e.kind().equals("STACK_TRACE")).findFirst()
                .filter(s -> codeEvidence.map(c -> !c.id().equals(s.id())).orElse(true))
                .ifPresent(s -> claim(claims, "The failure surfaces in " + scenario.suspectSymbol() + ".", s.id()));
        if (claims.isEmpty()) {
            claim(claims, "Suspect line located by reading the code.", evidence.isEmpty() ? "E1" : evidence.getFirst().id());
        }
        result.put("confidence", 0.85);
        return fin(result);
    }

    private ObjectNode fix(OfflineScenarios.Scenario scenario, String service, String first, List<String> toolResults) {
        String suspectPath = match(SUSPECT, first, 1).orElse(null);
        if (suspectPath == null) {
            ObjectNode empty = objectMapper.createObjectNode().put("summary", "No suspect location; no change proposed.")
                    .put("reproductionTestClass", scenario.reproClass());
            empty.putArray("edits");
            empty.putArray("newFiles");
            return fin(empty);
        }
        if (toolResults.isEmpty()) {
            return calls(call("code_read", objectMapper.createObjectNode().put("service", service)
                    .put("path", suspectPath).put("startLine", 1).put("endLine", 200)));
        }
        JsonNode file = fencedJson(toolResults.getLast());
        String content = file == null ? "" : file.path("content").asText();
        ObjectNode result = objectMapper.createObjectNode();
        result.put("reproductionTestClass", scenario.reproClass());
        ArrayNode edits = result.putArray("edits");
        ArrayNode newFiles = result.putArray("newFiles");
        if (content.contains(scenario.search())) {
            edits.addObject().put("path", suspectPath).put("search", scenario.search()).put("replace", scenario.replace());
            newFiles.addObject().put("path", scenario.reproPath()).put("content", scenario.reproTest());
            result.put("summary", scenario.summary());
            result.put("riskNotes", scenario.risk());
        } else {
            result.put("summary", "Could not locate the expected code in the suspect file; no change proposed.");
        }
        return fin(result);
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private ObjectNode lowConfidenceFinding(List<Evidence> evidence) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("hypothesis", "Could not map the failure to a specific line.");
        claim(result.putArray("claims"), "Failure evidence exists but the responsible code could not be located.",
                evidence.isEmpty() ? "E1" : evidence.getFirst().id());
        result.put("confidence", 0.2);
        return result;
    }

    private List<Evidence> evidence(String text) {
        List<Evidence> out = new ArrayList<>();
        Matcher m = EVIDENCE.matcher(text);
        while (m.find()) {
            out.add(new Evidence(m.group(1), m.group(2), Instant.parse(m.group(4)), m.group(7)));
        }
        return out;
    }

    private static Optional<Instant> onset(List<Evidence> evidence) {
        return evidence.stream().filter(Evidence::anomalousMetric).map(Evidence::observed)
                .min(Comparator.naturalOrder());
    }

    /** The most frequent WARN (or, unless warnOnly, WARN/ERROR) signature. */
    private static Optional<Evidence> hottest(List<Evidence> evidence, boolean warnOnly) {
        return evidence.stream()
                .filter(e -> warnOnly ? e.kind().equals("LOG_SIGNATURE") && e.text().startsWith("WARN")
                        : e.problemSignature())
                .max(Comparator.comparingLong(e -> match(COUNT, e.text(), 1).map(Long::parseLong).orElse(0L)));
    }

    private static List<String> touched(Evidence change, Set<String> failingFiles) {
        List<String> touched = new ArrayList<>();
        Matcher m = JAVA_FILE.matcher(change.text());
        while (m.find()) {
            if (failingFiles.contains(m.group(1)) && !touched.contains(m.group(1))) {
                touched.add(m.group(1));
            }
        }
        return touched;
    }

    private static int lastLine(String[] lines, int startLine, String marker) {
        int found = startLine;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                found = startLine + i;
            }
        }
        return found;
    }

    private static String message(String signatureText) {
        int colon = signatureText.indexOf(": ");
        return colon < 0 ? signatureText : signatureText.substring(colon + 2);
    }

    private JsonNode fencedJson(String toolResult) {
        Matcher m = FENCED.matcher(toolResult);
        if (!m.find()) {
            return null;
        }
        try {
            return objectMapper.readTree(m.group(2));
        } catch (Exception e) {
            return null;
        }
    }

    private static Optional<String> match(Pattern pattern, String text, int group) {
        Matcher m = pattern.matcher(text);
        return m.find() ? Optional.of(m.group(group)) : Optional.empty();
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    private static String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    private void ids(ObjectNode node, Set<String> ids) {
        ArrayNode array = node.putArray("evidenceIds");
        ids.forEach(array::add);
    }

    private void claim(ArrayNode claims, String statement, String... ids) {
        ObjectNode claim = objectMapper.createObjectNode().put("statement", abbreviate(statement, 590));
        ArrayNode array = claim.putArray("evidenceIds");
        for (String id : new LinkedHashSet<>(List.of(ids))) {
            array.add(id);
        }
        claims.add(claim);
    }

    private ObjectNode call(String tool, ObjectNode arguments) {
        ObjectNode call = objectMapper.createObjectNode().put("tool", tool);
        call.set("arguments", arguments);
        return call;
    }

    private ObjectNode calls(ObjectNode... calls) {
        ObjectNode envelope = objectMapper.createObjectNode().put("action", "call_tools");
        ArrayNode array = envelope.putArray("calls");
        for (ObjectNode call : calls) {
            array.add(call);
        }
        return envelope;
    }

    private ObjectNode fin(ObjectNode result) {
        ObjectNode envelope = objectMapper.createObjectNode().put("action", "final");
        envelope.set("result", result);
        return envelope;
    }
}
