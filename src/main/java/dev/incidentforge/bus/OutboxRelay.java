package dev.incidentforge.bus;

import dev.incidentforge.store.CommandStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Relays committed commands from the outbox table to the transport. A crash between send and
 * mark-published re-sends the same command id, which the processor deduplicates.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final CommandStore commands;
    private final CommandTransport transport;

    public OutboxRelay(CommandStore commands, CommandTransport transport) {
        this.commands = commands;
        this.transport = transport;
    }

    @Scheduled(fixedDelayString = "${incidentforge.outbox.poll-interval:200ms}")
    public synchronized void relay() {
        for (CommandStore.OutboxRow row : commands.pending(50)) {
            try {
                transport.send(row.runId(), row.payload());
                commands.markPublished(row.id());
            } catch (RuntimeException e) {
                commands.markAttempt(row.id());
                Throwable root = e;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                log.warn("outbox relay failed for command row {} (attempt {}): {} caused by {}: {}", row.id(),
                        row.attempts() + 1, e.getClass().getSimpleName(), root.getClass().getSimpleName(),
                        root.getMessage());
                return;
            }
        }
    }
}
