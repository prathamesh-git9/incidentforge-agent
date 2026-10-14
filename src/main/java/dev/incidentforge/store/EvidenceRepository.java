package dev.incidentforge.store;

import static dev.incidentforge.store.Db.instant;
import static dev.incidentforge.store.Db.ts;

import dev.incidentforge.domain.EvidenceItem;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EvidenceRepository {

    private final JdbcClient jdbc;

    public EvidenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(String runId, List<EvidenceItem> items) {
        for (EvidenceItem item : items) {
            jdbc.sql("""
                    INSERT INTO evidence (run_id, evidence_id, kind, source_tool, tool_call_id, observed_at,
                                          content_sha256, summary, flags)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(runId, item.evidenceId(), item.kind().name(), item.sourceTool(), item.toolCallId(),
                            ts(item.observedAt()), item.contentSha256(), item.summary(),
                            String.join(",", new TreeSet<>(item.flags())))
                    .update();
        }
    }

    public List<EvidenceItem> findByRun(String runId) {
        return jdbc.sql("SELECT * FROM evidence WHERE run_id = ? "
                        + "ORDER BY CAST(SUBSTRING(evidence_id, 2) AS INT)")
                .param(runId)
                .query((rs, n) -> new EvidenceItem(
                        rs.getString("evidence_id"),
                        EvidenceItem.Kind.valueOf(rs.getString("kind")),
                        rs.getString("source_tool"),
                        rs.getString("tool_call_id"),
                        instant(rs, "observed_at"),
                        rs.getString("content_sha256"),
                        rs.getString("summary"),
                        parseFlags(rs.getString("flags"))))
                .list();
    }

    private static Set<String> parseFlags(String flags) {
        if (flags == null || flags.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(flags.split(",")).collect(Collectors.toUnmodifiableSet());
    }
}
