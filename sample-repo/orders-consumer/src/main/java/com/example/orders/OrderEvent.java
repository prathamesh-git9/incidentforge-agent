package com.example.orders;

import java.util.Objects;

/** A parsed order event. Amounts are kept in minor units (cents) to avoid floating point. */
public record OrderEvent(String orderId, String customerId, long amountMinor, String currency) {

    public OrderEvent {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(currency, "currency");
    }
}
