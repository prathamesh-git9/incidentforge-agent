package dev.incidentforge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incidentforge.config.IncidentForgeProperties;
import dev.incidentforge.support.TestSupport;
import dev.incidentforge.telemetry.SyntheticTelemetry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * The complete vertical slice, offline: sparse report -> MCP evidence collection -> classification
 * -> parallel investigation -> mitigation -> fix -> reproduce-then-fix validation -> reviewer
 * correction -> approval of an exact hash -> local draft PR bundle. No credentials, no broker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"offline", "test"})
@AutoConfigureObservability
@ExtendWith(OutputCaptureExtension.class)
class OfflineEndToEndTest {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    IncidentForgeProperties properties;

    @Test
    void sparseReportBecomesAnEvidenceBackedValidatedDraftOnlyAfterHumanApproval(CapturedOutput output)
            throws Exception {
        TestRestTemplate reporter = rest.withBasicAuth("reporter", "demo-reporter-only");
        TestRestTemplate reviewer = rest.withBasicAuth("reviewer", "demo-reviewer-only");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "e2e-incident-0001");
        HttpEntity<String> report = new HttpEntity<>(TestSupport.sampleIncident(), headers);
        ResponseEntity<JsonNode> accepted = reporter.exchange("/api/incidents", HttpMethod.POST, report, JsonNode.class);
        assertThat(accepted.getStatusCode().value()).isEqualTo(202);
        String runId = accepted.getBody().path("runId").asText();

        ResponseEntity<JsonNode> again = reporter.exchange("/api/incidents", HttpMethod.POST, report, JsonNode.class);
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getBody().path("runId").asText()).isEqualTo(runId);

        JsonNode run = awaitStatus(reviewer, runId, "AWAITING_APPROVAL");

        // Evidence timeline: provenance on every item, secrets redacted, injection flagged (not obeyed)
        Set<String> evidenceIds = new HashSet<>();
        for (JsonNode e : run.path("evidence")) {
            evidenceIds.add(e.path("evidenceId").asText());
            assertThat(e.path("toolCallId").asText()).isNotBlank();
            assertThat(e.path("contentSha256").asText()).hasSize(64);
            assertThat(e.path("summary").asText()).doesNotContain(SyntheticTelemetry.FAKE_LEAKED_TOKEN);
        }
        assertThat(run.path("evidence").findValues("flags").toString())
                .contains("PROMPT_INJECTION_SUSPECTED", "REDACTED");
        assertThat(run.path("evidence").toString()).contains("27361 log lines", "14 MCP pages");

        assertThat(run.at("/classification/category").asText()).isEqualTo("APPLICATION_CODE");
        assertThat(run.at("/rootCause/suspectLocation/path").asText()).endsWith("OrderEventProcessor.java");
        assertThat(run.at("/rootCause/suspectLocation/verified").asBoolean()).isTrue();
        assertThat(run.at("/rootCause/rejectedClaims").size()).isZero();
        for (JsonNode claim : run.at("/rootCause/claims")) {
            claim.path("evidenceIds").forEach(id -> assertThat(evidenceIds).contains(id.asText()));
        }
        assertThat(run.at("/mitigation/action").asText()).isEqualTo("ROLLBACK_DEPLOY");
        assertThat(run.at("/mitigation/target").asText()).isEqualTo("1.8.3");
        assertThat(run.at("/mitigation/executed").asBoolean()).isFalse();
        // The skip-offset alternative must name the poison record, not a healthy partition from an INFO line.
        assertThat(run.at("/mitigation/alternatives").toString())
                .contains("SKIP_POISON_OFFSET: advance the consumer group past partition 3 offset 918274");

        assertThat(run.at("/validation/verdict").asText()).isEqualTo("REPRODUCED_THEN_FIXED");
        assertThat(run.at("/validation/beforeFix/exitCode").asInt()).isNotZero();
        assertThat(run.at("/validation/afterFix/exitCode").asInt()).isZero();
        assertThat(run.at("/patch/unifiedDiff").asText()).contains("deadLetters.publish(record");

        assertThat(run.path("toolCalls").findValuesAsText("decision")).containsOnly("ALLOWED");
        assertThat(run.path("modelCalls").findValuesAsText("actor")).contains("classifier", "timeline-investigator",
                "change-investigator", "code-path-investigator", "fixer");
        assertThat(run.path("statusReason").asText()).contains(run.path("patchSha256").asText());

        // Approval boundary
        String sha = run.path("patchSha256").asText();
        assertThat(post(reporter, runId, "/decision", Map.of("decision", "APPROVE", "patchSha256", sha)).getStatusCode()
                .value()).isEqualTo(403);
        assertThat(post(reviewer, runId, "/decision", Map.of("decision", "APPROVE", "patchSha256", "0".repeat(64)))
                .getStatusCode().value()).isEqualTo(409);

        // Reviewer correction goes back to the fixer and is re-validated before it can be approved
        assertThat(post(reviewer, runId, "/corrections",
                Map.of("comment", "Keep the WARN log line so the on-call dashboards still match.")).getStatusCode()
                .value()).isEqualTo(200);
        run = awaitStatus(reviewer, runId, "AWAITING_APPROVAL");
        assertThat(run.path("correctionRounds").asInt()).isEqualTo(1);
        assertThat(run.path("reviews").findValuesAsText("action")).contains("CORRECTION");
        assertThat(run.path("modelCalls").findValuesAsText("actor").stream().filter("fixer"::equals)).hasSize(4);

        sha = run.path("patchSha256").asText();
        assertThat(post(reviewer, runId, "/decision", Map.of("decision", "APPROVE", "patchSha256", sha,
                "comment", "reviewed evidence and diff")).getStatusCode().value()).isEqualTo(200);
        run = awaitStatus(reviewer, runId, "PUBLISHED");
        assertThat(run.path("approvedBy").asText()).isEqualTo("reviewer");

        Path bundle = properties.github().localOutbox().resolve(runId);
        String prBody = Files.readString(bundle.resolve("PULL_REQUEST.md"));
        assertThat(prBody).contains("IncidentForge cannot merge", "PROMPT_INJECTION_SUSPECTED", sha);
        assertTakeOverLocallyPatchApplies(bundle.resolve("change.patch"));

        // Nothing sensitive or prompt-shaped reaches the application log
        assertThat(output.getAll()).doesNotContain(SyntheticTelemetry.FAKE_LEAKED_TOKEN,
                "ignore previous instructions", "demo-reviewer-only", "demo-mcp-only", "<<data-", "amount=12,50");

        String metrics = rest.getForObject("/actuator/prometheus", String.class);
        assertThat(metrics).contains("incidentforge_commands_total", "incidentforge_tool_calls_total",
                "incidentforge_stage_duration_seconds", "incidentforge_evidence_injection_flags_total");
    }

    private static void assertTakeOverLocallyPatchApplies(Path patch) throws Exception {
        Path copy = Files.createTempDirectory("takeover");
        org.springframework.util.FileSystemUtils.copyRecursively(TestSupport.SAMPLE_REPO, copy);
        Process process;
        try {
            process = new ProcessBuilder("git", "apply", "--check", patch.toAbsolutePath().toString())
                    .directory(copy.toFile()).redirectErrorStream(true).start();
        } catch (java.io.IOException e) {
            Assumptions.abort("git is not installed");
            return;
        }
        String out = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as("git apply --check: " + out).isZero();
        org.springframework.util.FileSystemUtils.deleteRecursively(copy);
    }

    private ResponseEntity<JsonNode> post(TestRestTemplate client, String runId, String path, Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return client.exchange("/api/runs/" + runId + path, HttpMethod.POST, new HttpEntity<>(body, headers),
                JsonNode.class);
    }

    private static JsonNode awaitStatus(TestRestTemplate client, String runId, String status) {
        JsonNode[] last = new JsonNode[1];
        TestSupport.await("run " + runId + " to reach " + status, Duration.ofMinutes(4), () -> {
            last[0] = client.getForObject("/api/runs/" + runId, JsonNode.class);
            String current = last[0].path("status").asText();
            if (!current.equals(status) && !current.equals("IN_PROGRESS") && !current.equals("PUBLISHING")) {
                throw new AssertionError("run ended in " + current + ": " + last[0].path("statusReason").asText()
                        + " timeline=" + last[0].path("timeline"));
            }
            return current.equals(status);
        });
        return last[0];
    }
}
