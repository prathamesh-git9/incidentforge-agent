package dev.incidentforge.store;

import static dev.incidentforge.store.Db.ts;

import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Transactional outbox, processed-command ledger and dead-letter record. */
@Repository
public class CommandStore {

    private final JdbcClient jdbc;

    public CommandStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record OutboxRow(long id, String commandId, String runId, String payload, int attempts) {
    }

    public void enqueue(String commandId, String runId, String payload) {
        jdbc.sql("INSERT INTO command_outbox (command_id, run_id, payload, created_at, attempts) VALUES (?, ?, ?, ?, 0)")
                .params(commandId, runId, payload, ts(Instant.now()))
                .update();
    }

    public List<OutboxRow> pending(int limit) {
        return jdbc.sql("SELECT id, command_id, run_id, payload, attempts FROM command_outbox "
                        + "WHERE published_at IS NULL ORDER BY id LIMIT ?")
                .param(limit)
                .query((rs, n) -> new OutboxRow(rs.getLong("id"), rs.getString("command_id"), rs.getString("run_id"),
                        rs.getString("payload"), rs.getInt("attempts")))
                .list();
    }

    public void markPublished(long id) {
        jdbc.sql("UPDATE command_outbox SET published_at = ? WHERE id = ?").params(ts(Instant.now()), id).update();
    }

    public void markAttempt(long id) {
        jdbc.sql("UPDATE command_outbox SET attempts = attempts + 1 WHERE id = ?").param(id).update();
    }

    public boolean isProcessed(String commandId) {
        return jdbc.sql("SELECT COUNT(*) FROM processed_commands WHERE command_id = ?").param(commandId)
                .query(Integer.class).single() > 0;
    }

    /** Returns false if the command id was already recorded (a concurrent or repeated delivery). */
    public boolean markProcessed(String commandId, String runId, String stage, String outcome) {
        try {
            jdbc.sql("INSERT INTO processed_commands (command_id, run_id, stage, outcome, processed_at) VALUES (?, ?, ?, ?, ?)")
                    .params(commandId, runId, stage, outcome, ts(Instant.now()))
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public int processedCount(String runId) {
        return jdbc.sql("SELECT COUNT(*) FROM processed_commands WHERE run_id = ?").param(runId)
                .query(Integer.class).single();
    }

    public void recordDeadLetter(String runId, String reason, String payloadSha256) {
        jdbc.sql("INSERT INTO dead_letters (run_id, reason, payload_sha256, received_at) VALUES (?, ?, ?, ?)")
                .params(runId, RunRepository.truncate(reason, 1000), payloadSha256, ts(Instant.now()))
                .update();
    }

    public int deadLetterCount() {
        return jdbc.sql("SELECT COUNT(*) FROM dead_letters").query(Integer.class).single();
    }
}
