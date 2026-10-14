package dev.incidentforge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incidentforge.domain.IncidentReport;
import dev.incidentforge.domain.RunRecord;
import dev.incidentforge.domain.RunStatus;
import dev.incidentforge.domain.Stage;
import dev.incidentforge.pipeline.RunCommand;
import dev.incidentforge.repo.ServiceCatalog;
import dev.incidentforge.store.CommandStore;
import dev.incidentforge.store.RunRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Accepts a sparse incident report and starts a run (incident, run and first command in one transaction). */
@Service
public class IntakeService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,100}$");

    public record Accepted(String incidentId, String runId, boolean duplicate) {
    }

    private final RunRepository runs;
    private final CommandStore commands;
    private final ServiceCatalog services;
    private final ObjectMapper objectMapper;

    public IntakeService(RunRepository runs, CommandStore commands, ServiceCatalog services, ObjectMapper objectMapper) {
        this.runs = runs;
        this.commands = commands;
        this.services = services;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Accepted submit(IncidentReport report, String idempotencyKey) {
        validate(report, idempotencyKey);
        if (idempotencyKey != null) {
            var existing = runs.findIncidentIdByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                String runId = runs.findRunByIncident(existing.get()).map(RunRecord::id).orElseThrow();
                return new Accepted(existing.get(), runId, true);
            }
        }
        String incidentId = "inc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String runId = RunCommand.newRunId();
        runs.insertIncident(incidentId, idempotencyKey, report.service(), toJson(report));
        Instant now = Instant.now();
        runs.insertRun(new RunRecord(runId, incidentId, report.service(), RunStatus.IN_PROGRESS, Stage.COLLECT_CONTEXT,
                0, 0, 0, null, null, null, null, null, null, null, null, null, null, null, now, now));
        runs.addTimeline(runId, null, "INFO", "incident " + incidentId + " accepted from " + report.reportedBy());
        RunCommand first = RunCommand.next(runId, Stage.COLLECT_CONTEXT, 0);
        commands.enqueue(first.commandId(), runId, first.toJson(objectMapper));
        return new Accepted(incidentId, runId, false);
    }

    private void validate(IncidentReport report, String idempotencyKey) {
        if (report == null || report.service() == null || services.find(report.service()).isEmpty()) {
            throw new IllegalArgumentException("service is not allowlisted for investigation");
        }
        if (report.summary() == null || report.summary().isBlank() || report.summary().length() > 500) {
            throw new IllegalArgumentException("summary is required (max 500 chars)");
        }
        if (report.reportedBy() == null || report.reportedBy().length() > 80) {
            throw new IllegalArgumentException("reportedBy is required (max 80 chars)");
        }
        if (report.environment() == null || report.environment().length() > 40) {
            throw new IllegalArgumentException("environment is required (max 40 chars)");
        }
        if (report.metadata().size() > 20) {
            throw new IllegalArgumentException("at most 20 metadata entries");
        }
        for (Map.Entry<String, String> entry : report.metadata().entrySet()) {
            if (entry.getKey().length() > 40 || entry.getValue() == null || entry.getValue().length() > 200) {
                throw new IllegalArgumentException("metadata keys max 40 chars, values max 200 chars");
            }
        }
        if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("invalid Idempotency-Key");
        }
    }

    private String toJson(IncidentReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
