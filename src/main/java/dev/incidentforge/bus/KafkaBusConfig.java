package dev.incidentforge.bus;

import dev.incidentforge.config.IncidentForgeProperties;
import dev.incidentforge.pipeline.PoisonCommandException;
import dev.incidentforge.pipeline.RunCommandProcessor;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka-backed job progression. Commands are keyed by run id (per-run ordering), the producer is
 * idempotent with acks=all, offsets are committed per record after processing, transient failures
 * get a bounded FixedBackOff, and poison or exhausted records go to {@value #DLT}.
 */
@Configuration
@ConditionalOnProperty(name = "incidentforge.bus", havingValue = "kafka")
public class KafkaBusConfig {

    public static final String TOPIC = "incidentforge.run-commands";
    public static final String DLT = "incidentforge.run-commands.DLT";

    @Bean
    NewTopic runCommandsTopic() {
        return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic runCommandsDeadLetterTopic() {
        return TopicBuilder.name(DLT).partitions(3).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, IncidentForgeProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(DLT, record.partition()));
        FixedBackOff backOff = new FixedBackOff(properties.pipeline().transientBackoff().toMillis(),
                properties.pipeline().transientRetries());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(PoisonCommandException.class);
        return handler;
    }

    @Bean
    CommandTransport kafkaCommandTransport(KafkaTemplate<String, String> template) {
        return (runId, payload) -> {
            try {
                template.send(TOPIC, runId, payload).get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while publishing command", e);
            } catch (ExecutionException | TimeoutException e) {
                throw new IllegalStateException("command not acknowledged by Kafka", e);
            }
        };
    }

    @Component
    @ConditionalOnProperty(name = "incidentforge.bus", havingValue = "kafka")
    static class RunCommandListener {

        private final RunCommandProcessor processor;
        private final DeadLetterService deadLetters;

        RunCommandListener(RunCommandProcessor processor, DeadLetterService deadLetters) {
            this.processor = processor;
            this.deadLetters = deadLetters;
        }

        @KafkaListener(id = "run-commands", groupId = "incidentforge-pipeline", topics = TOPIC, concurrency = "3")
        void onCommand(ConsumerRecord<String, String> record) {
            processor.process(record.value());
        }

        @KafkaListener(id = "run-commands-dlt", groupId = "incidentforge-dead-letters", topics = DLT)
        void onDeadLetter(ConsumerRecord<String, String> record) {
            Header reason = record.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE);
            deadLetters.deadLetter(record.value(),
                    reason == null ? "dead-lettered" : new String(reason.value(), StandardCharsets.UTF_8));
        }
    }
}
