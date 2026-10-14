package dev.incidentforge.store;

import static dev.incidentforge.store.Db.instant;
import static dev.incidentforge.store.Db.ts;

import dev.incidentforge.domain.RunRecord;
import dev.incidentforge.domain.RunStatus;
import dev.incidentforge.domain.Stage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RunRepository {

    private final JdbcClient jdbc;

    public RunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record TimelineEntry(Instant at, String stage, String kind, String message) {
    }

    public record ReviewEvent(String actor, String action, String comment, String patchSha256, Instant at) {
    }

    public void insertIncident(String id, String idempotencyKey, String service, String reportJson) {
        jdbc.sql("INSERT INTO incidents (id, idempotency_key, service, report_json, created_at) VALUES (?, ?, ?, ?, ?)")
                .params(id, idempotencyKey, service, reportJson, ts(Instant.now()))
                .update();
    }

    public Optional<String> findIncidentIdByIdempotencyKey(String key) {
        return jdbc.sql("SELECT id FROM incidents WHERE idempotency_key = ?").param(key)
                .query(String.class).optional();
    }

    public String incidentReportJson(String incidentId) {
        return jdbc.sql("SELECT report_json FROM incidents WHERE id = ?").param(incidentId)
                .query(String.class).single();
    }

    public void insertRun(RunRecord run) {
        jdbc.sql("""
                INSERT INTO runs (id, incident_id, service, status, stage, version, fix_attempts, correction_rounds,
                                  created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, 0, 0, ?, ?)""")
                .params(run.id(), run.incidentId(), run.service(), run.status().name(),
                        run.stage() == null ? null : run.stage().name(), ts(run.createdAt()), ts(run.updatedAt()))
                .update();
    }

    public Optional<RunRecord> findRun(String runId) {
        return jdbc.sql("SELECT * FROM runs WHERE id = ?").param(runId).query(RunRepository::mapRun).optional();
    }

    public Optional<RunRecord> findRunByIncident(String incidentId) {
        return jdbc.sql("SELECT * FROM runs WHERE incident_id = ? ORDER BY created_at DESC")
                .param(incidentId).query(RunRepository::mapRun).stream().findFirst();
    }

    /** Optimistic concurrency: returns false if another writer advanced the run first. */
    public boolean update(RunRecord run) {
        int updated = jdbc.sql("""
                UPDATE runs SET status = ?, stage = ?, version = version + 1, fix_attempts = ?, correction_rounds = ?,
                    classification_json = ?, rca_json = ?, mitigation_json = ?, patch_json = ?, patch_sha256 = ?,
                    validation_json = ?, fix_feedback = ?, publish_json = ?, approved_by = ?, approved_patch_sha256 = ?,
                    failure_reason = ?, updated_at = ?
                WHERE id = ? AND version = ?""")
                .params(run.status().name(), run.stage() == null ? null : run.stage().name(), run.fixAttempts(),
                        run.correctionRounds(), run.classificationJson(), run.rcaJson(), run.mitigationJson(),
                        run.patchJson(), run.patchSha256(), run.validationJson(), run.fixFeedback(),
                        run.publishJson(), run.approvedBy(), run.approvedPatchSha256(),
                        truncate(run.failureReason(), 1000), ts(run.updatedAt()), run.id(), run.version())
                .update();
        return updated == 1;
    }

    public void addTimeline(String runId, Stage stage, String kind, String message) {
        jdbc.sql("INSERT INTO run_timeline (run_id, at, stage, kind, message) VALUES (?, ?, ?, ?, ?)")
                .params(runId, ts(Instant.now()), stage == null ? null : stage.name(), kind, truncate(message, 2000))
                .update();
    }

    public List<TimelineEntry> timeline(String runId) {
        return jdbc.sql("SELECT at, stage, kind, message FROM run_timeline WHERE run_id = ? ORDER BY id")
                .param(runId)
                .query((rs, n) -> new TimelineEntry(instant(rs, "at"), rs.getString("stage"), rs.getString("kind"),
                        rs.getString("message")))
                .list();
    }

    public void addReviewEvent(String runId, String actor, String action, String comment, String patchSha256) {
        jdbc.sql("INSERT INTO review_events (run_id, actor, action, comment, patch_sha256, created_at) VALUES (?, ?, ?, ?, ?, ?)")
                .params(runId, actor, action, truncate(comment, 2000), patchSha256, ts(Instant.now()))
                .update();
    }

    public List<ReviewEvent> reviewEvents(String runId) {
        return jdbc.sql("SELECT actor, action, comment, patch_sha256, created_at FROM review_events WHERE run_id = ? ORDER BY id")
                .param(runId)
                .query((rs, n) -> new ReviewEvent(rs.getString("actor"), rs.getString("action"),
                        rs.getString("comment"), rs.getString("patch_sha256"), instant(rs, "created_at")))
                .list();
    }

    private static RunRecord mapRun(ResultSet rs, int rowNum) throws SQLException {
        String stage = rs.getString("stage");
        return new RunRecord(
                rs.getString("id"),
                rs.getString("incident_id"),
                rs.getString("service"),
                RunStatus.valueOf(rs.getString("status")),
                stage == null ? null : Stage.valueOf(stage),
                rs.getInt("version"),
                rs.getInt("fix_attempts"),
                rs.getInt("correction_rounds"),
                rs.getString("classification_json"),
                rs.getString("rca_json"),
                rs.getString("mitigation_json"),
                rs.getString("patch_json"),
                rs.getString("patch_sha256"),
                rs.getString("validation_json"),
                rs.getString("fix_feedback"),
                rs.getString("publish_json"),
                rs.getString("approved_by"),
                rs.getString("approved_patch_sha256"),
                rs.getString("failure_reason"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }
}
