package com.example.orders;

import java.util.HashMap;
import java.util.Map;

/** In-memory ledger of per-customer order totals. */
public final class OrderLedger {

    private final Map<String, Long> totalsByCustomer = new HashMap<>();
    private int appliedCount;

    public void apply(OrderEvent event) {
        totalsByCustomer.merge(event.customerId(), event.amountMinor(), Long::sum);
        appliedCount++;
    }

    public long totalFor(String customerId) {
        return totalsByCustomer.getOrDefault(customerId, 0L);
    }

    public int appliedCount() {
        return appliedCount;
    }
}
