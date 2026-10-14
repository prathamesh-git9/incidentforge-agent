package dev.incidentforge.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incidentforge.domain.Stage;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Message on the run-commands topic: "execute {@code stage} for {@code runId}, expecting the run
 * to be at {@code expectedVersion}". The command id is durable (generated once, stored in the
 * outbox) so every redelivery of the same message carries the same id.
 */
public record RunCommand(String commandId, String runId, Stage stage, int expectedVersion, Instant issuedAt) {

    private static final Pattern RUN_ID = Pattern.compile("^run-[0-9a-f]{12}$");

    public static RunCommand next(String runId, Stage stage, int expectedVersion) {
        return new RunCommand(UUID.randomUUID().toString(), runId, stage, expectedVersion, Instant.now());
    }

    public String toJson(ObjectMapper objectMapper) {
        return objectMapper.createObjectNode()
                .put("commandId", commandId)
                .put("runId", runId)
                .put("stage", stage.name())
                .put("expectedVersion", expectedVersion)
                .put("issuedAt", issuedAt.toString())
                .toString();
    }

    /** Strict parsing: anything malformed is a poison message, never retried. */
    public static RunCommand parse(String payload, ObjectMapper objectMapper) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String commandId = UUID.fromString(node.path("commandId").asText()).toString();
            String runId = node.path("runId").asText();
            if (!RUN_ID.matcher(runId).matches()) {
                throw new IllegalArgumentException("invalid runId");
            }
            Stage stage = Stage.valueOf(node.path("stage").asText());
            JsonNode version = node.path("expectedVersion");
            if (!version.isInt() || version.asInt() < 0) {
                throw new IllegalArgumentException("invalid expectedVersion");
            }
            return new RunCommand(commandId, runId, stage, version.asInt(), Instant.parse(node.path("issuedAt").asText()));
        } catch (Exception e) {
            throw new PoisonCommandException("unparseable run command: " + e.getClass().getSimpleName(), e);
        }
    }

    public static String newRunId() {
        return "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
