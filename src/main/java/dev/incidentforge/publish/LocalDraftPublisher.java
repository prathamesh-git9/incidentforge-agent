package dev.incidentforge.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incidentforge.domain.Artifacts.PublishResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Offline publisher: writes a reviewable PR bundle (diff, PR description, metadata) to a local
 * outbox instead of calling GitHub. Idempotent on the patch hash.
 */
public class LocalDraftPublisher implements PullRequestPublisher {

    private final Path outbox;
    private final ObjectMapper objectMapper;

    public LocalDraftPublisher(Path outbox, ObjectMapper objectMapper) {
        this.outbox = outbox.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    @Override
    public PublishResult publish(Request request) {
        Path dir = outbox.resolve(request.runId());
        Path metadata = dir.resolve("metadata.json");
        try {
            if (Files.exists(metadata)) {
                JsonNode existing = objectMapper.readTree(metadata.toFile());
                if (request.patchSha256().equals(existing.path("patchSha256").asText())) {
                    return new PublishResult("local", "PUBLISHED", dir.toUri().toString(), request.branch(), null,
                            "bundle already written for this patch (idempotent re-delivery)");
                }
                throw new PublishConflictException("a different patch was already published for run " + request.runId());
            }
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("change.patch"), request.unifiedDiff(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("PULL_REQUEST.md"), "# " + request.title() + "\n\n" + request.body(),
                    StandardCharsets.UTF_8);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("runId", request.runId());
            meta.put("branch", request.branch());
            meta.put("patchSha256", request.patchSha256());
            meta.put("draft", true);
            meta.put("createdAt", Instant.now().toString());
            // Written last: its presence marks the bundle complete.
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(metadata.toFile(), meta);
            return new PublishResult("local", "PUBLISHED", dir.toUri().toString(), request.branch(), null,
                    "draft PR bundle written; review PULL_REQUEST.md and apply with 'git apply change.patch'");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
