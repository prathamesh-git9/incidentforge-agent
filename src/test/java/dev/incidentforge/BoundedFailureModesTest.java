package dev.incidentforge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incidentforge.llm.ModelClient;
import dev.incidentforge.llm.OfflineModelClient;
import dev.incidentforge.support.TestSupport;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

/** Retries and turns are bounded: a failing or looping model ends the run visibly instead of spinning. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"offline", "test"})
@Import(BoundedFailureModesTest.Config.class)
class BoundedFailureModesTest {

    enum Mode { OVERLOADED, LOOPING }

    static volatile Mode mode;
    static final AtomicInteger classifierCalls = new AtomicInteger();

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ModelClient misbehavingClassifier(ObjectMapper objectMapper) {
            OfflineModelClient honest = new OfflineModelClient(objectMapper);
            return new ModelClient() {
                @Override
                public String provider() {
                    return "test-double";
                }

                @Override
                public Reply complete(Request request) {
                    if (!request.actor().equals("classifier")) {
                        return honest.complete(request);
                    }
                    classifierCalls.incrementAndGet();
                    if (mode == Mode.OVERLOADED) {
                        throw new TransientModelException("simulated 529 overloaded", null);
                    }
                    return new Reply("{\"action\":\"call_tools\",\"calls\":[{\"tool\":\"logs_search\",\"arguments\":"
                            + "{\"service\":\"orders-consumer\",\"from\":\"2026-10-12T10:00:00Z\","
                            + "\"to\":\"2026-10-12T11:00:00Z\"}}]}", 10, 10, "end_turn", null);
                }
            };
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void persistentTransientFailureIsRetriedABoundedNumberOfTimesThenFailsTheRun() {
        mode = Mode.OVERLOADED;
        classifierCalls.set(0);
        JsonNode run = runUntilStopped();

        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(run.path("statusReason").asText()).contains("retries", "overloaded");
        assertThat(classifierCalls.get()).isEqualTo(3); // first attempt + transient-retries (2)
    }

    @Test
    void aModelThatNeverFinishesIsStoppedByItsTurnBudget() {
        mode = Mode.LOOPING;
        classifierCalls.set(0);
        JsonNode run = runUntilStopped();

        assertThat(run.path("status").asText()).isEqualTo("NEEDS_HUMAN");
        assertThat(run.path("statusReason").asText()).contains("classification did not complete", "2 turns");
        assertThat(classifierCalls.get()).isEqualTo(2);
        assertThat(run.path("toolCalls").toString()).contains("not permitted in stage CLASSIFY");
    }

    private JsonNode runUntilStopped() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String runId = rest.withBasicAuth("reporter", "demo-reporter-only").exchange("/api/incidents",
                HttpMethod.POST, new HttpEntity<>(TestSupport.sampleIncident(), headers), JsonNode.class)
                .getBody().path("runId").asText();
        TestRestTemplate reviewer = rest.withBasicAuth("reviewer", "demo-reviewer-only");
        JsonNode[] run = new JsonNode[1];
        TestSupport.await("run to stop", Duration.ofMinutes(1), () -> {
            run[0] = reviewer.getForObject("/api/runs/" + runId, JsonNode.class);
            return !run[0].path("status").asText().equals("IN_PROGRESS");
        });
        return run[0];
    }
}
