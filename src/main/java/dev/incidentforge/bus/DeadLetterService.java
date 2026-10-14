package dev.incidentforge.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incidentforge.domain.RunStatus;
import dev.incidentforge.pipeline.PoisonCommandException;
import dev.incidentforge.pipeline.RunCommand;
import dev.incidentforge.store.CommandStore;
import dev.incidentforge.store.RunRepository;
import dev.incidentforge.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Terminal handling for commands that were poison or exhausted their retries. Every dead letter
 * is recorded (payload hash only). A run is failed only when the payload is a well-formed command
 * that is still the run's current step: an unparseable or stale message is untrusted input and
 * must not be able to change the state of a healthy run just by naming its id.
 */
@Component
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final CommandStore commands;
    private final RunRepository runs;
    private final ObjectMapper objectMapper;

    public DeadLetterService(CommandStore commands, RunRepository runs, ObjectMapper objectMapper) {
        this.commands = commands;
        this.runs = runs;
        this.objectMapper = objectMapper;
    }

    public void deadLetter(String payload, String reason) {
        String body = payload == null ? "" : payload;
        RunCommand command;
        try {
            command = RunCommand.parse(body, objectMapper);
        } catch (PoisonCommandException e) {
            commands.recordDeadLetter(null, abbreviate(reason), Hashing.sha256(body));
            log.warn("poison command dead-lettered (unparseable; no run state changed)");
            return;
        }
        commands.recordDeadLetter(command.runId(), abbreviate(reason), Hashing.sha256(body));
        log.warn("command dead-lettered runId={} stage={}", command.runId(), command.stage());
        runs.findRun(command.runId())
                .filter(run -> !run.status().isTerminal())
                .filter(run -> run.stage() == command.stage() && run.version() == command.expectedVersion())
                .ifPresent(run -> {
                    if (runs.update(run.toBuilder().status(RunStatus.FAILED).stage(null)
                            .failureReason("command dead-lettered after bounded retries: " + abbreviate(reason))
                            .build())) {
                        runs.addTimeline(run.id(), run.stage(), "STOP", "command dead-lettered: " + abbreviate(reason));
                    }
                });
    }

    private static String abbreviate(String reason) {
        return reason == null || reason.length() <= 300 ? reason : reason.substring(0, 297) + "...";
    }
}
