package dev.incidentforge.store;

import static dev.incidentforge.store.Db.instant;
import static dev.incidentforge.store.Db.ts;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Append-only audit of every tool call (allowed or denied) and every model call. */
@Repository
public class AuditRepository {

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ToolCallRecord(String callId, String runId, String stage, String actor, String tool,
                                 String argsSha256, String decision, String reason, long latencyMs,
                                 String resultSha256, int resultChars, Instant at) {
    }

    public record ModelCallRecord(String runId, String stage, String actor, String provider, String model, int turn,
                                  String promptSha256, int inputChars, int outputChars, long inputTokens,
                                  long outputTokens, String outcome, long latencyMs, Instant at) {
    }

    public void recordToolCall(ToolCallRecord r) {
        jdbc.sql("""
                INSERT INTO tool_calls (call_id, run_id, stage, actor, tool, args_sha256, decision, reason, latency_ms,
                                        result_sha256, result_chars, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(r.callId(), r.runId(), r.stage(), r.actor(), RunRepository.truncate(r.tool(), 80),
                        r.argsSha256(), r.decision(), RunRepository.truncate(r.reason(), 500), r.latencyMs(),
                        r.resultSha256(), r.resultChars(), ts(r.at()))
                .update();
    }

    public void recordModelCall(ModelCallRecord r) {
        jdbc.sql("""
                INSERT INTO model_calls (run_id, stage, actor, provider, model, turn, prompt_sha256, input_chars,
                                         output_chars, input_tokens, output_tokens, outcome, latency_ms, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(r.runId(), r.stage(), RunRepository.truncate(r.actor(), 40),
                        RunRepository.truncate(r.provider(), 40), RunRepository.truncate(r.model(), 60), r.turn(),
                        r.promptSha256(),
                        r.inputChars(), r.outputChars(), r.inputTokens(), r.outputTokens(), r.outcome(),
                        r.latencyMs(), ts(r.at()))
                .update();
    }

    public List<ToolCallRecord> toolCalls(String runId) {
        return jdbc.sql("SELECT * FROM tool_calls WHERE run_id = ? ORDER BY created_at, call_id").param(runId)
                .query((rs, n) -> new ToolCallRecord(rs.getString("call_id"), rs.getString("run_id"),
                        rs.getString("stage"), rs.getString("actor"), rs.getString("tool"),
                        rs.getString("args_sha256"), rs.getString("decision"), rs.getString("reason"),
                        rs.getLong("latency_ms"), rs.getString("result_sha256"), rs.getInt("result_chars"),
                        instant(rs, "created_at")))
                .list();
    }

    public List<ModelCallRecord> modelCalls(String runId) {
        return jdbc.sql("SELECT * FROM model_calls WHERE run_id = ? ORDER BY id").param(runId)
                .query((rs, n) -> new ModelCallRecord(rs.getString("run_id"), rs.getString("stage"),
                        rs.getString("actor"), rs.getString("provider"), rs.getString("model"), rs.getInt("turn"),
                        rs.getString("prompt_sha256"), rs.getInt("input_chars"), rs.getInt("output_chars"),
                        rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getString("outcome"),
                        rs.getLong("latency_ms"), instant(rs, "created_at")))
                .list();
    }
}
