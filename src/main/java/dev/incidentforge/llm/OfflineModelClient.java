package dev.incidentforge.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, credential-free stand-in for a model. It is a SCRIPTED heuristic that reads
 * the same prompts a real model receives and answers in the same JSON envelope, including
 * multi-turn tool use. It exists so the whole pipeline (MCP tools, budgets, validation, policy,
 * approval, publishing) can be run and tested offline. It demonstrates the plumbing and the
 * guardrails, not model reasoning quality.
 */
public class OfflineModelClient implements ModelClient {

    // Text blocks strip common indentation; re-indent to the 12 spaces used inside pollOnce().
    static final String CATCH_BLOCK = """
            } catch (MalformedEventException e) {
                LOG.log(Level.WARNING, "malformed event partition={0} offset={1}; rewinding for retry",
                        record.partition(), record.offset());
                source.seek(record.offset());
                return applied;
            }""".indent(12).stripTrailing();

    static final String FIXED_CATCH_BLOCK = """
            } catch (MalformedEventException e) {
                // A malformed record fails identically on every redelivery (poison event):
                // dead-letter it and commit past it instead of rewinding the partition.
                LOG.log(Level.WARNING, "malformed event partition={0} offset={1}; dead-lettering",
                        record.partition(), record.offset());
                deadLetters.publish(record, "malformed: " + e.getMessage());
                source.commit(record.offset() + 1);
            }""".indent(12).stripTrailing();

    static final String REPRO_TEST = """
            package com.example.orders;

            import static org.junit.jupiter.api.Assertions.assertEquals;
            import static org.junit.jupiter.api.Assertions.assertTrue;

            import java.util.ArrayList;
            import java.util.List;
            import org.junit.jupiter.api.Test;

            /** Reproduces the incident: one malformed record must not stall the partition. */
            class PoisonEventReproTest {

                @Test
                void malformedEventIsDeadLetteredAndDoesNotBlockThePartition() {
                    InMemoryRecordSource source = new InMemoryRecordSource(List.of(
                            "orderId=A-1;customerId=C-1;amount=10.00;currency=EUR",
                            "orderId=A-2;customerId=C-2;amount=12,50;currency=EUR",
                            "orderId=A-3;customerId=C-3;amount=7.25;currency=EUR"));
                    OrderLedger ledger = new OrderLedger();
                    List<String> deadLettered = new ArrayList<>();
                    OrderEventProcessor processor = new OrderEventProcessor(source, new OrderEventParser(), ledger,
                            (record, reason) -> deadLettered.add(record.offset() + ":" + reason));

                    // Bounded: with a rewind-and-retry loop the partition never gets past offset 1.
                    for (int i = 0; i < 5; i++) {
                        processor.pollOnce();
                    }

                    assertEquals(3, source.committedOffset());
                    assertEquals(2, ledger.appliedCount());
                    assertEquals(1, deadLettered.size());
                    assertTrue(deadLettered.get(0).startsWith("1:malformed"));
                }
            }
            """;

    private static final Pattern EVIDENCE = Pattern.compile(
            "\\[(E\\d+)] kind=(\\w+) source=(\\S+) observed=(\\S+) flags=(\\S*)\\n<<(data-[0-9a-f]+)\\n(.*?)\\n\\6>>",
            Pattern.DOTALL);
    private static final Pattern FENCED = Pattern.compile("<<(data-[0-9a-f]+)\\n(.*?)\\n\\1>>", Pattern.DOTALL);
    private static final Pattern SERVICE = Pattern.compile("service=([a-z][a-z0-9-]+)");
    private static final Pattern COUNT = Pattern.compile(" x(\\d+) ");
    private static final Pattern SUSPECT = Pattern.compile("Suspect location: (\\S+?):(\\d+)");

    private record Evidence(String id, String kind, String text) {
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
        ObjectNode reply = switch (request.actor()) {
            case "classifier" -> classify(evidence(first));
            case "timeline-investigator" -> timeline(evidence(first));
            case "change-investigator" -> change(evidence(first));
            case "code-path-investigator" -> codePath(first, evidence(first), toolResults);
            case "fixer" -> fix(first, toolResults);
            default -> fin(objectMapper.createObjectNode());
        };
        String text = reply.toString();
        return new Reply(text, first.length() / 4, text.length() / 4, "end_turn", null);
    }

    private ObjectNode classify(List<Evidence> evidence) {
        Optional<Evidence> stack = first(evidence, "STACK_TRACE");
        Optional<Evidence> hot = hottestSignature(evidence);
        Optional<Evidence> lag = evidence.stream().filter(e -> e.text().contains("consumer_lag")).findFirst();
        ObjectNode result = objectMapper.createObjectNode();
        if (stack.isPresent() && stack.get().text().contains("com.example") && hot.isPresent()) {
            result.put("category", "APPLICATION_CODE").put("codeFixRecommended", true).put("confidence", 0.8)
                    .put("rationale", "A single log signature repeats thousands of times for one partition/offset "
                            + "and the stack frames are in the service's own package; lag is isolated to that partition.");
            ids(result, hot.get().id(), stack.get().id(), lag.map(Evidence::id).orElse(stack.get().id()));
        } else {
            result.put("category", "UNKNOWN").put("codeFixRecommended", false).put("confidence", 0.3)
                    .put("rationale", "No stack trace in the service's own code was collected.");
            ids(result, evidence.isEmpty() ? "E1" : evidence.getFirst().id());
        }
        return fin(result);
    }

    private ObjectNode timeline(List<Evidence> evidence) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("hypothesis", "Since the onset one partition has been re-processing the same offset continuously: "
                + "its lag grows linearly while other partitions stay flat, and the pod that owns it is CPU-saturated.");
        ArrayNode claims = result.putArray("claims");
        evidence.stream().filter(e -> e.text().contains("consumer_lag")).findFirst()
                .ifPresent(e -> claim(claims, "Consumer lag rises on a single partition from the onset.", e.id()));
        hottestSignature(evidence).ifPresent(e -> claim(claims,
                "The same 'rewinding for retry' warning repeats for one partition/offset roughly ten times per second.",
                e.id()));
        evidence.stream().filter(e -> e.text().contains("cpu_utilization")).findFirst()
                .ifPresent(e -> claim(claims, "CPU on the pod owning that partition jumps at the onset.", e.id()));
        evidence.stream().filter(e -> e.text().contains("InventoryClient")).findFirst()
                .ifPresent(e -> claim(claims, "Inventory timeouts stop before the onset and are unrelated.", e.id()));
        result.put("confidence", 0.7);
        return fin(result);
    }

    private ObjectNode change(List<Evidence> evidence) {
        String stackText = first(evidence, "STACK_TRACE").map(Evidence::text).orElse("");
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode claims = objectMapper.createArrayNode();
        Optional<Evidence> deploy = evidence.stream()
                .filter(e -> e.kind().equals("DEPLOY"))
                .filter(e -> touchesStack(e.text(), stackText))
                .reduce((a, b) -> b);
        deploy.ifPresent(d -> claim(claims, "The deploy immediately before the onset changed files that appear in the "
                + "failing stack (parser strictness and retry handling).", d.id(),
                first(evidence, "STACK_TRACE").map(Evidence::id).orElse(d.id())));
        evidence.stream().filter(e -> e.kind().equals("FEATURE_FLAG")).findFirst().ifPresent(f -> claim(claims,
                "The flag change before the onset touches ledger writes, which do not appear in the failing stack.",
                f.id()));
        result.put("hypothesis", deploy.isPresent()
                ? firstLine(deploy.get().text())
                : "No deploy or flag change correlates with the failing code path.");
        result.set("claims", claims.isEmpty() ? claims.add(claimNode("No correlated change found.",
                evidence.getFirst().id())) : claims);
        result.put("confidence", deploy.isPresent() ? 0.75 : 0.2);
        return fin(result);
    }

    private ObjectNode codePath(String first, List<Evidence> evidence, List<String> toolResults) {
        String service = match(SERVICE, first, 1).orElse("unknown");
        Optional<Evidence> hot = hottestSignature(evidence);
        Optional<Evidence> stack = first(evidence, "STACK_TRACE");
        if (toolResults.isEmpty()) {
            String phrase = hot.map(e -> e.text().substring(e.text().lastIndexOf(';') + 1).strip())
                    .orElse("Exception");
            return calls(call("code_search", objectMapper.createObjectNode().put("service", service)
                    .put("query", phrase.length() > 100 ? phrase.substring(0, 100) : phrase)));
        }
        JsonNode lastResult = fencedJson(toolResults.getLast());
        if (toolResults.size() == 1) {
            JsonNode match = lastResult == null ? null : lastResult.path("matches").path(0);
            if (match == null || match.isMissingNode()) {
                return fin(lowConfidenceFinding(stack));
            }
            int line = match.path("line").asInt();
            return calls(call("code_read", objectMapper.createObjectNode().put("service", service)
                    .put("path", match.path("path").asText()).put("startLine", Math.max(1, line - 16))
                    .put("endLine", line + 8)));
        }
        if (lastResult == null || !lastResult.has("content")) {
            return fin(lowConfidenceFinding(stack));
        }
        String[] lines = lastResult.path("content").asText().split("\n", -1);
        int startLine = lastResult.path("startLine").asInt(1);
        int seekLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(".seek(")) {
                seekLine = startLine + i;
            }
        }
        if (seekLine < 0) {
            return fin(lowConfidenceFinding(stack));
        }
        int suspectLine = seekLine;
        ObjectNode result = objectMapper.createObjectNode();
        result.put("hypothesis", "OrderEventProcessor.pollOnce catches MalformedEventException and seeks back to the "
                + "same offset, so a record that can never parse is redelivered forever (hot loop, no progress).");
        result.put("suspectPath", lastResult.path("path").asText()).put("suspectLine", suspectLine)
                .put("suspectSymbol", "OrderEventProcessor.pollOnce");
        ArrayNode claims = result.putArray("claims");
        stack.ifPresent(s -> claim(claims, "The parse failure surfaces in OrderEventProcessor.pollOnce.", s.id()));
        hot.ifPresent(h -> claim(claims, "The handler logs 'rewinding for retry' and calls source.seek(record.offset()) "
                + "at line " + suspectLine + ", re-queuing the same record.", h.id()));
        result.put("confidence", 0.85);
        return fin(result);
    }

    private ObjectNode fix(String first, List<String> toolResults) {
        String service = match(SERVICE, first, 1).orElse("unknown");
        String suspectPath = match(SUSPECT, first, 1).orElse("src/main/java/com/example/orders/OrderEventProcessor.java");
        if (toolResults.isEmpty()) {
            return calls(call("code_read", objectMapper.createObjectNode().put("service", service)
                    .put("path", suspectPath).put("startLine", 1).put("endLine", 200)));
        }
        JsonNode file = fencedJson(toolResults.getLast());
        String content = file == null ? "" : file.path("content").asText();
        ObjectNode result = objectMapper.createObjectNode();
        result.put("reproductionTestClass", "PoisonEventReproTest");
        ArrayNode edits = result.putArray("edits");
        ArrayNode newFiles = result.putArray("newFiles");
        if (content.contains(CATCH_BLOCK)) {
            edits.addObject().put("path", suspectPath).put("search", CATCH_BLOCK).put("replace", FIXED_CATCH_BLOCK);
            newFiles.addObject().put("path", "src/test/java/com/example/orders/PoisonEventReproTest.java")
                    .put("content", REPRO_TEST);
            result.put("summary", "Treat MalformedEventException as a poison record: dead-letter it with the reason and "
                    + "commit past it instead of seeking back to the same offset.");
            result.put("riskNotes", "Malformed events are no longer retried; they are preserved in the dead-letter sink "
                    + "for replay after the data is corrected.");
        } else {
            result.put("summary", "Could not locate the retry handler in the suspect file; no change proposed.");
        }
        return fin(result);
    }

    private ObjectNode lowConfidenceFinding(Optional<Evidence> stack) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("hypothesis", "Could not map the failure to a specific line.");
        claim(result.putArray("claims"), "A stack trace exists but the handler could not be located.",
                stack.map(Evidence::id).orElse("E1"));
        result.put("confidence", 0.2);
        return result;
    }

    private List<Evidence> evidence(String text) {
        List<Evidence> out = new ArrayList<>();
        Matcher m = EVIDENCE.matcher(text);
        while (m.find()) {
            out.add(new Evidence(m.group(1), m.group(2), m.group(7)));
        }
        return out;
    }

    private static Optional<Evidence> first(List<Evidence> evidence, String kind) {
        return evidence.stream().filter(e -> e.kind().equals(kind)).findFirst();
    }

    private static Optional<Evidence> hottestSignature(List<Evidence> evidence) {
        return evidence.stream()
                .filter(e -> e.kind().equals("LOG_SIGNATURE") && e.text().startsWith("WARN"))
                .max(Comparator.comparingLong(e -> match(COUNT, e.text(), 1).map(Long::parseLong).orElse(0L)));
    }

    private static boolean touchesStack(String deployText, String stackText) {
        for (String file : List.of("OrderEventProcessor.java", "OrderEventParser.java")) {
            if (deployText.contains(file) && stackText.contains(file)) {
                return true;
            }
        }
        return false;
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

    private void ids(ObjectNode node, String... ids) {
        ArrayNode array = node.putArray("evidenceIds");
        for (String id : ids) {
            array.add(id);
        }
    }

    private void claim(ArrayNode claims, String statement, String... ids) {
        claims.add(claimNode(statement, ids));
    }

    private ObjectNode claimNode(String statement, String... ids) {
        ObjectNode claim = objectMapper.createObjectNode().put("statement", statement);
        ArrayNode array = claim.putArray("evidenceIds");
        for (String id : ids) {
            array.add(id);
        }
        return claim;
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
