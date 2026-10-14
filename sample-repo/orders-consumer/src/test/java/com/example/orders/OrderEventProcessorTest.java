package com.example.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderEventProcessorTest {

    @Test
    void appliesEventsAndCommitsOffsets() {
        InMemoryRecordSource source = new InMemoryRecordSource(List.of(
                "orderId=A-1;customerId=C-1;amount=10.00;currency=EUR",
                "orderId=A-2;customerId=C-1;amount=2.50;currency=EUR"));
        OrderLedger ledger = new OrderLedger();
        OrderEventProcessor processor = new OrderEventProcessor(source, new OrderEventParser(), ledger,
                (record, reason) -> {
                    throw new AssertionError("unexpected dead letter: " + reason);
                });

        assertEquals(2, processor.pollOnce());
        assertEquals(2, source.committedOffset());
        assertEquals(1250L, ledger.totalFor("C-1"));
    }

    @Test
    void tombstonesAreDeadLetteredAndSkipped() {
        InMemoryRecordSource source = new InMemoryRecordSource(Arrays.asList(
                null, "orderId=A-1;customerId=C-1;amount=1.00;currency=EUR"));
        List<String> deadLettered = new ArrayList<>();
        OrderEventProcessor processor = new OrderEventProcessor(source, new OrderEventParser(), new OrderLedger(),
                (record, reason) -> deadLettered.add(record.offset() + ":" + reason));

        assertEquals(1, processor.pollOnce());
        assertEquals(List.of("0:tombstone"), deadLettered);
        assertEquals(2, source.committedOffset());
    }
}
