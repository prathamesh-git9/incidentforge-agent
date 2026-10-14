package dev.incidentforge.support;

import dev.incidentforge.config.IncidentForgeProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

public final class TestSupport {

    public static final Path SAMPLE_REPO = Path.of("sample-repo", "orders-consumer");

    private TestSupport() {
    }

    /** Binds the real application.yml + offline + test profiles, without starting Spring. */
    public static IncidentForgeProperties properties() {
        StandardEnvironment env = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        try {
            for (String file : List.of("application.yml", "application-offline.yml", "application-test.yml")) {
                loader.load(file, new ClassPathResource(file)).forEach(ps -> env.getPropertySources().addFirst(ps));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Binder.get(env).bind("incidentforge", IncidentForgeProperties.class).get();
    }

    public static void await(String description, Duration timeout, Supplier<Boolean> condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + description);
            }
        }
        throw new AssertionError("timed out after " + timeout + " waiting for " + description);
    }

    public static String sampleIncident() {
        return incident("orders-consumer-lag.json");
    }

    public static String incident(String fixture) {
        try {
            return Files.readString(Path.of("samples", "incidents", fixture));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Files a fixture as the reporter and returns the run id. */
    public static String submit(org.springframework.boot.test.web.client.TestRestTemplate rest, String fixture) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return rest.withBasicAuth("reporter", "demo-reporter-only")
                .exchange("/api/incidents", org.springframework.http.HttpMethod.POST,
                        new org.springframework.http.HttpEntity<>(incident(fixture), headers),
                        com.fasterxml.jackson.databind.JsonNode.class)
                .getBody().path("runId").asText();
    }

    /** Polls the run as the reviewer until it reaches {@code status}; fails fast if it stops elsewhere. */
    public static com.fasterxml.jackson.databind.JsonNode awaitRun(
            org.springframework.boot.test.web.client.TestRestTemplate rest, String runId, String status) {
        org.springframework.boot.test.web.client.TestRestTemplate reviewer =
                rest.withBasicAuth("reviewer", "demo-reviewer-only");
        com.fasterxml.jackson.databind.JsonNode[] last = new com.fasterxml.jackson.databind.JsonNode[1];
        await("run " + runId + " to reach " + status, Duration.ofMinutes(4), () -> {
            last[0] = reviewer.getForObject("/api/runs/" + runId, com.fasterxml.jackson.databind.JsonNode.class);
            String current = last[0].path("status").asText();
            if (!current.equals(status) && !current.equals("IN_PROGRESS") && !current.equals("PUBLISHING")) {
                throw new AssertionError("run ended in " + current + ": " + last[0].path("statusReason").asText()
                        + " timeline=" + last[0].path("timeline"));
            }
            return current.equals(status);
        });
        return last[0];
    }

    /** The evidence item whose summary contains {@code text}. */
    public static com.fasterxml.jackson.databind.JsonNode evidence(com.fasterxml.jackson.databind.JsonNode run,
                                                                   String kind, String text) {
        for (com.fasterxml.jackson.databind.JsonNode e : run.path("evidence")) {
            if (e.path("kind").asText().equals(kind) && e.path("summary").asText().contains(text)) {
                return e;
            }
        }
        throw new AssertionError("no " + kind + " evidence containing '" + text + "' in " + run.path("evidence"));
    }
}
