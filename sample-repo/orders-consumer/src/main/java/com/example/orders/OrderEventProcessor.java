package com.example.orders;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Objects;

/**
 * Consumes order events from one partition and applies them to the ledger.
 * Offsets are committed only after an event has been applied.
 */
public final class OrderEventProcessor {

    private static final Logger LOG = System.getLogger(OrderEventProcessor.class.getName());
    private static final int MAX_POLL_RECORDS = 100;

    private final RecordSource source;
    private final OrderEventParser parser;
    private final OrderLedger ledger;
    private final DeadLetterSink deadLetters;

    public OrderEventProcessor(RecordSource source, OrderEventParser parser,
                               OrderLedger ledger, DeadLetterSink deadLetters) {
        this.source = Objects.requireNonNull(source);
        this.parser = Objects.requireNonNull(parser);
        this.ledger = Objects.requireNonNull(ledger);
        this.deadLetters = Objects.requireNonNull(deadLetters);
    }

    /** Polls one batch and processes it. Returns the number of events applied to the ledger. */
    public int pollOnce() {
        List<SourceRecord> batch = source.poll(MAX_POLL_RECORDS);
        int applied = 0;
        for (SourceRecord record : batch) {
            if (record.value() == null) {
                // Tombstones carry no order data; route them away and move on.
                deadLetters.publish(record, "tombstone");
                source.commit(record.offset() + 1);
                continue;
            }
            try {
                OrderEvent event = parser.parse(record.value());
                ledger.apply(event);
                source.commit(record.offset() + 1);
                applied++;
            } catch (MalformedEventException e) {
                LOG.log(Level.WARNING, "malformed event partition={0} offset={1}; rewinding for retry",
                        record.partition(), record.offset());
                source.seek(record.offset());
                return applied;
            }
        }
        return applied;
    }
}
