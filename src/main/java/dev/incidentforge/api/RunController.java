package dev.incidentforge.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incidentforge.domain.EvidenceItem;
import dev.incidentforge.domain.IncidentReport;
import dev.incidentforge.domain.RunRecord;
import dev.incidentforge.pipeline.ArtifactJson;
import dev.incidentforge.store.AuditRepository;
import dev.incidentforge.store.EvidenceRepository;
import dev.incidentforge.store.RunRepository;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RunController {

    public record Decision(String decision, String patchSha256, String comment) {
    }

    public record Correction(String comment) {
    }

    /** Everything a reviewer needs to check the agent's work, including what it was not allowed to do. */
    public record RunView(String runId, String incidentId, String service, String status, String stage,
                          String statusReason, int fixAttempts, int correctionRounds, JsonNode classification,
                          JsonNode rootCause, JsonNode mitigation, JsonNode patch, String patchSha256,
                          JsonNode validation, JsonNode publish, String approvedBy, List<EvidenceItem> evidence,
                          List<RunRepository.TimelineEntry> timeline, List<AuditRepository.ToolCallRecord> toolCalls,
                          List<AuditRepository.ModelCallRecord> modelCalls, List<RunRepository.ReviewEvent> reviews) {
    }

    private final IntakeService intake;
    private final ReviewService review;
    private final RunRepository runs;
    private final EvidenceRepository evidence;
    private final AuditRepository audit;
    private final ArtifactJson json;

    public RunController(IntakeService intake, ReviewService review, RunRepository runs, EvidenceRepository evidence,
                         AuditRepository audit, ArtifactJson json) {
        this.intake = intake;
        this.review = review;
        this.runs = runs;
        this.evidence = evidence;
        this.audit = audit;
        this.json = json;
    }

    @PostMapping("/incidents")
    public ResponseEntity<IntakeService.Accepted> submit(@RequestBody IncidentReport report,
                                                         @RequestHeader(value = "Idempotency-Key", required = false)
                                                         String idempotencyKey) {
        IntakeService.Accepted accepted = intake.submit(report, idempotencyKey);
        return ResponseEntity.status(accepted.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .location(URI.create("/api/runs/" + accepted.runId())).body(accepted);
    }

    @GetMapping("/runs/{runId}")
    public RunView run(@PathVariable String runId) {
        RunRecord run = runs.findRun(runId).orElseThrow(() -> new NoSuchElementException("run not found"));
        return new RunView(run.id(), run.incidentId(), run.service(), run.status().name(),
                run.stage() == null ? null : run.stage().name(), run.failureReason(), run.fixAttempts(),
                run.correctionRounds(), json.tree(run.classificationJson()), json.tree(run.rcaJson()),
                json.tree(run.mitigationJson()), json.tree(run.patchJson()), run.patchSha256(),
                json.tree(run.validationJson()), json.tree(run.publishJson()), run.approvedBy(),
                evidence.findByRun(runId), runs.timeline(runId), audit.toolCalls(runId), audit.modelCalls(runId),
                runs.reviewEvents(runId));
    }

    /** "Take over locally": the validated diff, ready for {@code git apply}. */
    @GetMapping(value = "/runs/{runId}/patch", produces = "text/x-diff")
    public String patch(@PathVariable String runId) {
        RunRecord run = runs.findRun(runId).orElseThrow(() -> new NoSuchElementException("run not found"));
        JsonNode patch = json.tree(run.patchJson());
        if (patch == null) {
            throw new NoSuchElementException("run has no patch");
        }
        return patch.path("unifiedDiff").asText();
    }

    @PostMapping("/runs/{runId}/decision")
    public RunView decide(@PathVariable String runId, @RequestBody Decision decision, Principal reviewer) {
        switch (decision.decision() == null ? "" : decision.decision()) {
            case "APPROVE" -> review.approve(runId, reviewer.getName(), decision.patchSha256(), decision.comment());
            case "REJECT" -> review.reject(runId, reviewer.getName(), decision.comment());
            default -> throw new IllegalArgumentException("decision must be APPROVE or REJECT");
        }
        return run(runId);
    }

    @PostMapping("/runs/{runId}/corrections")
    public RunView correct(@PathVariable String runId, @RequestBody Correction correction, Principal reviewer) {
        review.correct(runId, reviewer.getName(), correction.comment());
        return run(runId);
    }

    @PostMapping("/runs/{runId}/publish/retry")
    public RunView retryPublish(@PathVariable String runId, Principal reviewer) {
        review.retryPublish(runId, reviewer.getName());
        return run(runId);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ReviewService.ReviewConflict.class)
    ResponseEntity<Map<String, String>> conflict(ReviewService.ReviewConflict e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", e.getMessage()));
    }
}
