package com.example.orders;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses the wire format {@code orderId=A-1;customerId=C-9;amount=12.50;currency=EUR}.
 *
 * <p>Since 1.8.3 amounts are parsed strictly: only a plain decimal with at most two
 * fraction digits is accepted.
 */
public final class OrderEventParser {

    public OrderEvent parse(String raw) throws MalformedEventException {
        if (raw == null || raw.isBlank()) {
            throw new MalformedEventException("empty event");
        }
        Map<String, String> fields = new HashMap<>();
        for (String pair : raw.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new MalformedEventException("invalid field '" + pair + "'");
            }
            fields.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        String orderId = required(fields, "orderId");
        String customerId = required(fields, "customerId");
        String currency = required(fields, "currency");
        long amountMinor = parseAmount(required(fields, "amount"));
        return new OrderEvent(orderId, customerId, amountMinor, currency);
    }

    private static long parseAmount(String amount) throws MalformedEventException {
        try {
            BigDecimal value = new BigDecimal(amount);
            if (value.scale() > 2 || value.signum() < 0) {
                throw new MalformedEventException("invalid amount '" + amount + "'");
            }
            return value.movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new MalformedEventException("invalid amount '" + amount + "'", e);
        }
    }

    private static String required(Map<String, String> fields, String name) throws MalformedEventException {
        String value = fields.get(name);
        if (value == null || value.isEmpty()) {
            throw new MalformedEventException("missing field '" + name + "'");
        }
        return value;
    }
}
