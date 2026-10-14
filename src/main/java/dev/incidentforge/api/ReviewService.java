package dev.incidentforge.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incidentforge.config.IncidentForgeProperties;
import dev.incidentforge.domain.RunRecord;
import dev.incidentforge.domain.RunStatus;
import dev.incidentforge.domain.Stage;
import dev.incidentforge.pipeline.RunCommand;
import dev.incidentforge.store.CommandStore;
import dev.incidentforge.store.RunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The human boundary. Approval binds a named reviewer to one exact patch hash; corrections are
 * recorded and fed back to the fixer (bounded); nothing is published without approval.
 */
@Service
public class ReviewService {

    public static class ReviewConflict extends RuntimeException {
        public ReviewConflict(String message) {
            super(message);
        }
    }

    private final RunRepository runs;
    private final CommandStore commands;
    private final ObjectMapper objectMapper;
    private final IncidentForgeProperties properties;

    public ReviewService(RunRepository runs, CommandStore commands, ObjectMapper objectMapper,
                         IncidentForgeProperties properties) {
        this.runs = runs;
        this.commands = commands;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    public RunRecord approve(String runId, String reviewer, String patchSha256, String comment) {
        RunRecord run = load(runId);
        if (run.status() != RunStatus.AWAITING_APPROVAL) {
            throw new ReviewConflict("run is " + run.status() + ", not AWAITING_APPROVAL");
        }
        if (patchSha256 == null || !patchSha256.equals(run.patchSha256())) {
            throw new ReviewConflict("patchSha256 does not match the current patch; reload and review again");
        }
        RunRecord next = run.toBuilder().status(RunStatus.PUBLISHING).stage(Stage.PUBLISH).approvedBy(reviewer)
                .approvedPatchSha256(patchSha256).failureReason(null).build();
        save(run, next);
        runs.addReviewEvent(runId, reviewer, "APPROVE", comment, patchSha256);
        runs.addTimeline(runId, Stage.PUBLISH, "REVIEW", "approved by " + reviewer + " for patch "
                + patchSha256.substring(0, 12));
        enqueue(run, Stage.PUBLISH);
        return next;
    }

    @Transactional
    public RunRecord reject(String runId, String reviewer, String comment) {
        RunRecord run = load(runId);
        if (run.status() != RunStatus.AWAITING_APPROVAL && run.status() != RunStatus.NEEDS_HUMAN) {
            throw new ReviewConflict("run is " + run.status() + "; only reviewable runs can be rejected");
        }
        RunRecord next = run.toBuilder().status(RunStatus.REJECTED).stage(null)
                .failureReason("rejected by " + reviewer).build();
        save(run, next);
        runs.addReviewEvent(runId, reviewer, "REJECT", comment, run.patchSha256());
        runs.addTimeline(runId, null, "REVIEW", "rejected by " + reviewer);
        return next;
    }

    /** Sends the reviewer's correction to the fixer as explicit input for a new, re-validated patch. */
    @Transactional
    public RunRecord correct(String runId, String reviewer, String comment) {
        RunRecord run = load(runId);
        if (run.status() != RunStatus.AWAITING_APPROVAL && run.status() != RunStatus.NEEDS_HUMAN) {
            throw new ReviewConflict("run is " + run.status() + "; corrections apply to reviewable runs");
        }
        if (run.rcaJson() == null) {
            throw new ReviewConflict("no RCA to correct yet");
        }
        if (run.correctionRounds() >= properties.pipeline().maxCorrectionRounds()) {
            throw new ReviewConflict("correction limit reached; take over locally with GET /api/runs/" + runId + "/patch");
        }
        if (comment == null || comment.isBlank() || comment.length() > 2000) {
            throw new IllegalArgumentException("correction comment is required (max 2000 chars)");
        }
        RunRecord next = run.toBuilder().status(RunStatus.IN_PROGRESS).stage(Stage.PROPOSE_FIX)
                .correctionRounds(run.correctionRounds() + 1).fixAttempts(0).fixFeedback(null)
                .patchJson(null).patchSha256(null).validationJson(null).failureReason(null).build();
        save(run, next);
        runs.addReviewEvent(runId, reviewer, "CORRECTION", comment, run.patchSha256());
        runs.addTimeline(runId, Stage.PROPOSE_FIX, "REVIEW", "correction from " + reviewer + " sent to the fixer");
        enqueue(run, Stage.PROPOSE_FIX);
        return next;
    }

    /** Safe to call repeatedly: publishing is idempotent and reconciles an uncertain earlier attempt. */
    @Transactional
    public RunRecord retryPublish(String runId, String reviewer) {
        RunRecord run = load(runId);
        if (run.status() != RunStatus.PUBLISH_UNCERTAIN) {
            throw new ReviewConflict("run is " + run.status() + ", not PUBLISH_UNCERTAIN");
        }
        RunRecord next = run.toBuilder().status(RunStatus.PUBLISHING).stage(Stage.PUBLISH).build();
        save(run, next);
        runs.addTimeline(runId, Stage.PUBLISH, "REVIEW", "publish retry requested by " + reviewer);
        enqueue(run, Stage.PUBLISH);
        return next;
    }

    private RunRecord load(String runId) {
        return runs.findRun(runId).orElseThrow(() -> new java.util.NoSuchElementException("run not found"));
    }

    private void save(RunRecord before, RunRecord next) {
        if (!runs.update(next)) {
            throw new ReviewConflict("run changed concurrently; reload and retry");
        }
    }

    private void enqueue(RunRecord before, Stage stage) {
        RunCommand command = RunCommand.next(before.id(), stage, before.version() + 1);
        commands.enqueue(command.commandId(), before.id(), command.toJson(objectMapper));
    }
}
