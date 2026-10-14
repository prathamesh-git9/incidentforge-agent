package dev.incidentforge;

import static dev.incidentforge.support.TestSupport.awaitRun;
import static dev.incidentforge.support.TestSupport.evidence;
import static dev.incidentforge.support.TestSupport.submit;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incidentforge.config.IncidentForgeProperties;
import dev.incidentforge.telemetry.CheckoutRolloutWorld;
import dev.incidentforge.tools.McpToolClient;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

/**
 * Third fixture: a bad feature rollout. Nothing was deployed; a flag ramp exposes a code path that
 * breaks for one region. The flag is the immediate, reversible mitigation — recommended, never
 * flipped by the agent — and the code fix is what makes turning it back on safe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"offline", "test"})
@AutoConfigureObservability
class FeatureRolloutIncidentTest {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    McpToolClient mcp;
    @Autowired
    IncidentForgeProperties properties;

    @Test
    void flagRampIsMitigatedByRecommendationAndFixedByRestoringTheFallback() throws Exception {
        String runId = submit(rest, "checkout-quote-errors.json");
        JsonNode run = awaitRun(rest, runId, "AWAITING_APPROVAL");

        // Evidence: one region's error rate, same version everywhere, flag ramp with code refs into the failure.
        JsonNode errors = evidence(run, "METRIC", "error_rate_percent region=CH version=3.2.1 rose from baseline");
        assertThat(errors.path("summary").asText()).contains("2 flat series");
        evidence(run, "METRIC", "payment_latency_p95_ms provider=gateway-a no sustained anomaly");
        JsonNode flag = evidence(run, "FEATURE_FLAG", "flag " + CheckoutRolloutWorld.FLAG);
        assertThat(flag.path("summary").asText()).contains("rollout=25%", "code refs: " + CheckoutRolloutWorld.V2_FILE);
        JsonNode trace = evidence(run, "TRACE", "POST /checkout/quote");
        assertThat(trace.path("summary").asText()).contains("checkout.region=CH",
                "feature_flag." + CheckoutRolloutWorld.FLAG + "=true", "service.version=3.2.1",
                "exception.type=java.lang.NullPointerException");
        evidence(run, "STACK_TRACE", "TaxCalculatorV2.taxMinor(TaxCalculatorV2.java:23)");

        // RCA: the flag is the trigger, the missing fallback is the cause; distractors are ruled out with citations.
        assertThat(run.at("/rootCause/suspectLocation/path").asText()).isEqualTo(CheckoutRolloutWorld.V2_FILE);
        assertThat(run.at("/rootCause/suspectLocation/line").asInt()).isEqualTo(23);
        assertThat(run.at("/rootCause/suspectLocation/verified").asBoolean()).isTrue();
        assertThat(run.at("/rootCause/contributingChange").asText()).startsWith("flag " + CheckoutRolloutWorld.FLAG);
        JsonNode deploy = evidence(run, "DEPLOY", "deploy 3.2.1");
        JsonNode expressPay = evidence(run, "FEATURE_FLAG", "checkout.express-pay");
        JsonNode gatewayWarnings = evidence(run, "LOG_SIGNATURE", "c.e.c.PaymentGatewayClient");
        assertThat(SlowReadPathIncidentTest.claimsCiting(run, deploy.path("evidenceId").asText()))
                .anySatisfy(s -> assertThat(s).startsWith("Ruled out"));
        assertThat(SlowReadPathIncidentTest.claimsCiting(run, expressPay.path("evidenceId").asText()))
                .anySatisfy(s -> assertThat(s).startsWith("Ruled out"));
        assertThat(SlowReadPathIncidentTest.claimsCiting(run, gatewayWarnings.path("evidenceId").asText()))
                .anySatisfy(s -> assertThat(s).contains("before and after the onset"));

        // Mitigation: disable the flag — a recommendation for a person; no flag write capability exists.
        assertThat(run.at("/mitigation/action").asText()).isEqualTo("DISABLE_FEATURE_FLAG");
        assertThat(run.at("/mitigation/target").asText()).isEqualTo(CheckoutRolloutWorld.FLAG);
        assertThat(run.at("/mitigation/executed").asBoolean()).isFalse();
        assertThat(run.at("/mitigation/risk").asText()).contains("no flag write access");
        assertThat(mcp.listToolNames()).noneMatch(t -> t.contains("set") || t.contains("update")
                || t.contains("toggle") || t.contains("write"));
        assertThat(run.path("toolCalls").findValuesAsText("decision")).containsOnly("ALLOWED");

        // Permanent fix: restore the legacy fallback; the reproduction test errors before and passes after.
        String diff = run.at("/patch/unifiedDiff").asText();
        assertThat(diff).contains("-        int rateBp = RATES_BP.get(region);",
                "+            return legacy.taxMinor(region, amountMinor);");
        assertThat(diff).doesNotContain("CheckoutService.java b/");
        assertThat(run.at("/validation/verdict").asText()).isEqualTo("REPRODUCED_THEN_FIXED");
        assertThat(run.at("/validation/beforeFix/outputTail").asText()).contains("TaxEngineFallbackReproTest",
                "NullPointer");

        // A human approves the exact hash; the PR bundle carries the mitigation as a separate, unexecuted step.
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        rest.withBasicAuth("reviewer", "demo-reviewer-only").exchange("/api/runs/" + runId + "/decision",
                HttpMethod.POST, new HttpEntity<>(Map.of("decision", "APPROVE",
                        "patchSha256", run.path("patchSha256").asText()), json), JsonNode.class);
        awaitRun(rest, runId, "PUBLISHED");
        String prBody = Files.readString(properties.github().localOutbox().resolve(runId).resolve("PULL_REQUEST.md"));
        assertThat(prBody).contains("Mitigation (recommended separately, not executed)",
                "DISABLE_FEATURE_FLAG `" + CheckoutRolloutWorld.FLAG + "`");
    }
}
