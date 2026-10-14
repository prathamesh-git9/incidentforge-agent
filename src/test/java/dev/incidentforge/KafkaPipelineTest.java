package dev.incidentforge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incidentforge.bus.KafkaBusConfig;
import dev.incidentforge.store.CommandStore;
import dev.incidentforge.support.TestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

/**
 * Same pipeline over a real (embedded, KRaft) Kafka broker: commands keyed by run id, at-least-once
 * delivery made safe by durable command ids, and poison messages dead-lettered without blocking
 * the partition.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "incidentforge.bus=kafka",
        "spring.autoconfigure.exclude=",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"})
@ActiveProfiles({"offline", "test"})
@EmbeddedKafka(partitions = 3, topics = {KafkaBusConfig.TOPIC, KafkaBusConfig.DLT})
class KafkaPipelineTest {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    CommandStore commands;
    @Autowired
    MeterRegistry meters;

    @Test
    void commandsFlowThroughKafkaAndRedeliveryOrPoisonCannotCorruptARun() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String runId = rest.withBasicAuth("reporter", "demo-reporter-only").exchange("/api/incidents", HttpMethod.POST,
                new HttpEntity<>(TestSupport.sampleIncident(), headers), JsonNode.class).getBody().path("runId").asText();
        TestRestTemplate reviewer = rest.withBasicAuth("reviewer", "demo-reviewer-only");
        JsonNode run = await(reviewer, runId, "AWAITING_APPROVAL");
        int version = jdbc.sql("SELECT version FROM runs WHERE id = ?").param(runId).query(Integer.class).single();
        int processed = commands.processedCount(runId);
        assertThat(processed).isEqualTo(6);

        // Redeliver every command this run has ever produced (simulates consumer crash/rebalance replays)
        List<String> payloads = jdbc.sql("SELECT payload FROM command_outbox WHERE run_id = ?").param(runId)
                .query(String.class).list();
        for (String payload : payloads) {
            kafka.send(KafkaBusConfig.TOPIC, runId, payload).get();
        }
        // Poison: malformed JSON and a structurally invalid command on the same partition key
        kafka.send(KafkaBusConfig.TOPIC, runId, "{not json").get();
        kafka.send(KafkaBusConfig.TOPIC, runId, "{\"commandId\":\"nope\",\"runId\":\"" + runId + "\"}").get();

        TestSupport.await("duplicates acknowledged and poison dead-lettered", Duration.ofSeconds(60), () ->
                count("duplicate") >= payloads.size() && commands.deadLetterCount() >= 2);
        assertThat(commands.processedCount(runId))
                .as("processed ledger %s", jdbc.sql("SELECT stage, outcome, command_id FROM processed_commands "
                        + "WHERE run_id = ? ORDER BY processed_at").param(runId).query().listOfRows())
                .isEqualTo(processed);
        assertThat(jdbc.sql("SELECT version FROM runs WHERE id = ?").param(runId).query(Integer.class).single())
                .isEqualTo(version);
        assertThat(reviewer.getForObject("/api/runs/" + runId, JsonNode.class).path("status").asText())
                .isEqualTo("AWAITING_APPROVAL");

        // The partition is not blocked: the run still completes after the poison messages
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        reviewer.exchange("/api/runs/" + runId + "/decision", HttpMethod.POST, new HttpEntity<>(Map.of(
                "decision", "APPROVE", "patchSha256", run.path("patchSha256").asText()), json), JsonNode.class);
        await(reviewer, runId, "PUBLISHED");
    }

    private double count(String outcome) {
        Counter counter = meters.find("incidentforge.commands").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private static JsonNode await(TestRestTemplate client, String runId, String status) {
        JsonNode[] last = new JsonNode[1];
        TestSupport.await("run " + runId + " to reach " + status, Duration.ofMinutes(4), () -> {
            last[0] = client.getForObject("/api/runs/" + runId, JsonNode.class);
            String current = last[0].path("status").asText();
            if (current.equals("NEEDS_HUMAN") || current.equals("FAILED")) {
                throw new AssertionError("run ended in " + current + ": " + last[0].path("statusReason").asText());
            }
            return current.equals(status);
        });
        return last[0];
    }
}
