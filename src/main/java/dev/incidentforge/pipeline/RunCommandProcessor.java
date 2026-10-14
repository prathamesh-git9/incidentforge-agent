package dev.incidentforge.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incidentforge.domain.IncidentReport;
import dev.incidentforge.domain.RunRecord;
import dev.incidentforge.domain.RunStatus;
import dev.incidentforge.llm.ModelClient;
import dev.incidentforge.observability.PipelineMetrics;
import dev.incidentforge.store.CommandStore;
import dev.incidentforge.store.EvidenceRepository;
import dev.incidentforge.store.RunRepository;
import dev.incidentforge.tools.ToolGateway;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes one run command, exactly-once in effect even though delivery is at-least-once:
 * <ul>
 *   <li>a command id already in {@code processed_commands} is a duplicate and is acknowledged;</li>
 *   <li>a command whose stage/version no longer matches the run is stale and is acknowledged;</li>
 *   <li>the new run state, its evidence, the processed marker and the next command (outbox) are
 *       committed in one transaction, guarded by an optimistic version check;</li>
 *   <li>transient failures are rethrown for the bus's bounded retry; malformed commands are poison.</li>
 * </ul>
 */
@Component
public class RunCommandProcessor {

    private static final Logger log = LoggerFactory.getLogger(RunCommandProcessor.class);

    public enum Result { PROCESSED, DUPLICATE, STALE }

    private final Map<dev.incidentforge.domain.Stage, StageHandler> handlers =
            new EnumMap<>(dev.incidentforge.domain.Stage.class);
    private final RunRepository runs;
    private final EvidenceRepository evidence;
    private final CommandStore commands;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final PipelineMetrics metrics;
    private final ObservationRegistry observations;

    public RunCommandProcessor(List<StageHandler> stageHandlers, RunRepository runs, EvidenceRepository evidence,
                               CommandStore commands, TransactionTemplate tx, ObjectMapper objectMapper,
                               PipelineMetrics metrics, ObservationRegistry observations) {
        stageHandlers.forEach(h -> handlers.put(h.stage(), h));
        this.runs = runs;
        this.evidence = evidence;
        this.commands = commands;
        this.tx = tx;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.observations = observations;
    }

    public Result process(String payload) {
        RunCommand command = RunCommand.parse(payload, objectMapper);
        MDC.put("runId", command.runId());
        MDC.put("stage", command.stage().name());
        try {
            return processCommand(command);
        } finally {
            MDC.remove("runId");
            MDC.remove("stage");
        }
    }

    private Result processCommand(RunCommand command) {
        if (commands.isProcessed(command.commandId())) {
            metrics.command("duplicate");
            log.info("duplicate delivery ignored commandId={}", command.commandId());
            return Result.DUPLICATE;
        }
        RunRecord run = runs.findRun(command.runId())
                .orElseThrow(() -> new PoisonCommandException("command for unknown run", null));
        boolean runnable = run.status() == RunStatus.IN_PROGRESS || run.status() == RunStatus.PUBLISHING;
        if (!runnable || run.stage() != command.stage() || run.version() != command.expectedVersion()) {
            commands.markProcessed(command.commandId(), run.id(), command.stage().name(), "STALE");
            metrics.command("stale");
            log.info("stale command ignored commandId={} runVersion={} expected={}", command.commandId(),
                    run.version(), command.expectedVersion());
            return Result.STALE;
        }
        StageHandler handler = handlers.get(command.stage());
        StageHandler.Context context = new StageHandler.Context(run,
                objectMapper.convertValue(readTree(runs.incidentReportJson(run.incidentId())), IncidentReport.class),
                evidence.findByRun(run.id()), runs.reviewEvents(run.id()));

        long started = System.nanoTime();
        StageHandler.Outcome outcome;
        try {
            outcome = Observation.createNotStarted("incidentforge.stage", observations)
                    .lowCardinalityKeyValue("stage", command.stage().name())
                    .observe(() -> handler.execute(context));
        } catch (TransientStageException e) {
            metrics.stage(command.stage().name(), "transient_error", elapsed(started));
            throw e;
        } catch (ModelClient.TransientModelException | ToolGateway.ToolTransportException e) {
            metrics.stage(command.stage().name(), "transient_error", elapsed(started));
            throw new TransientStageException(command.stage() + " failed transiently: " + e.getMessage(), e);
        } catch (ModelClient.ModelCallException | IllegalArgumentException | IllegalStateException
                 | UncheckedIOException e) {
            // Deterministic failure: retrying the same input would fail the same way. Stop for a human.
            log.warn("stage failed permanently: {}", e.getClass().getSimpleName());
            outcome = StageHandler.Outcome.from(run)
                    .stop(RunStatus.NEEDS_HUMAN, command.stage() + " failed: " + e.getMessage());
        }
        metrics.stage(command.stage().name(), outcome.next().status().name().toLowerCase(), elapsed(started));
        return commit(command, run, outcome);
    }

    private Result commit(RunCommand command, RunRecord before, StageHandler.Outcome outcome) {
        Boolean committed = tx.execute(status -> {
            if (!commands.markProcessed(command.commandId(), before.id(), command.stage().name(), "PROCESSED")) {
                status.setRollbackOnly();
                return false;
            }
            if (!runs.update(outcome.next())) {
                status.setRollbackOnly();
                return false;
            }
            evidence.insertAll(before.id(), outcome.newEvidence());
            for (StageHandler.Note note : outcome.notes()) {
                runs.addTimeline(before.id(), command.stage(), note.kind(), note.message());
            }
            RunRecord next = outcome.next();
            boolean continues = (next.status() == RunStatus.IN_PROGRESS || next.status() == RunStatus.PUBLISHING)
                    && next.stage() != null;
            if (continues) {
                RunCommand nextCommand = RunCommand.next(before.id(), next.stage(), before.version() + 1);
                commands.enqueue(nextCommand.commandId(), before.id(), nextCommand.toJson(objectMapper));
            }
            return true;
        });
        if (Boolean.TRUE.equals(committed)) {
            metrics.command("processed");
            log.info("stage committed status={} next={}", outcome.next().status(), outcome.next().stage());
            return Result.PROCESSED;
        }
        metrics.command("concurrent_conflict");
        log.info("stage result discarded: another delivery committed first commandId={}", command.commandId());
        return Result.STALE;
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new PoisonCommandException("incident report unreadable", e);
        }
    }

    private static Duration elapsed(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }
}
