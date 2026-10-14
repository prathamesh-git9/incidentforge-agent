package dev.incidentforge.bus;

import dev.incidentforge.config.IncidentForgeProperties;
import dev.incidentforge.pipeline.PoisonCommandException;
import dev.incidentforge.pipeline.RunCommandProcessor;
import dev.incidentforge.pipeline.TransientStageException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Broker-free transport for the offline demo and fast tests. Same outbox, same processor, same
 * failure classification as Kafka: poison is dead-lettered immediately, transient failures get
 * a bounded number of retries with backoff, then dead-lettered.
 */
@Component
@ConditionalOnProperty(name = "incidentforge.bus", havingValue = "in-memory")
public class InMemoryCommandTransport implements CommandTransport, DisposableBean {

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "incidentforge-inmemory-bus");
        thread.setDaemon(true);
        return thread;
    });
    private final RunCommandProcessor processor;
    private final DeadLetterService deadLetters;
    private final IncidentForgeProperties properties;

    public InMemoryCommandTransport(RunCommandProcessor processor, DeadLetterService deadLetters,
                                    IncidentForgeProperties properties) {
        this.processor = processor;
        this.deadLetters = deadLetters;
        this.properties = properties;
    }

    @Override
    public void send(String runId, String payload) {
        worker.submit(() -> deliver(payload));
    }

    void deliver(String payload) {
        int retries = properties.pipeline().transientRetries();
        for (int attempt = 0; ; attempt++) {
            try {
                processor.process(payload);
                return;
            } catch (PoisonCommandException e) {
                deadLetters.deadLetter(payload, "poison: " + e.getMessage());
                return;
            } catch (TransientStageException e) {
                if (attempt >= retries) {
                    deadLetters.deadLetter(payload, "retries exhausted: " + e.getMessage());
                    return;
                }
                sleep(properties.pipeline().transientBackoff().toMillis() * (attempt + 1));
            } catch (RuntimeException e) {
                deadLetters.deadLetter(payload, "unexpected " + e.getClass().getSimpleName());
                return;
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void destroy() {
        worker.shutdownNow();
    }
}
