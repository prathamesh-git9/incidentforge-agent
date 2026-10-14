package com.example.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OrderEventParserTest {

    private final OrderEventParser parser = new OrderEventParser();

    @Test
    void parsesWellFormedEvent() throws Exception {
        OrderEvent event = parser.parse("orderId=A-1;customerId=C-9;amount=12.50;currency=EUR");
        assertEquals("A-1", event.orderId());
        assertEquals(1250L, event.amountMinor());
    }

    @Test
    void rejectsCommaDecimalAmount() {
        assertThrows(MalformedEventException.class,
                () -> parser.parse("orderId=A-2;customerId=C-9;amount=12,50;currency=EUR"));
    }

    @Test
    void rejectsMissingField() {
        assertThrows(MalformedEventException.class, () -> parser.parse("orderId=A-3;amount=1.00;currency=EUR"));
    }
}
