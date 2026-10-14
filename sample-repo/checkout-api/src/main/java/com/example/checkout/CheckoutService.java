package com.example.checkout;

import java.util.Objects;

/** Prices a cart. The tax engine is chosen per customer by a feature flag. */
public final class CheckoutService {

    static final String NEW_TAX_ENGINE = "checkout.new-tax-engine";

    private final FeatureFlags flags;
    private final TaxCalculator legacy;
    private final TaxCalculator v2;

    public CheckoutService(FeatureFlags flags, TaxCalculator legacy, TaxCalculator v2) {
        this.flags = Objects.requireNonNull(flags);
        this.legacy = Objects.requireNonNull(legacy);
        this.v2 = Objects.requireNonNull(v2);
    }

    public Quote quote(Cart cart, String customerId) {
        TaxCalculator calculator = flags.enabled(NEW_TAX_ENGINE, customerId) ? v2 : legacy;
        long tax = calculator.taxMinor(cart.region(), cart.subtotalMinor());
        return new Quote(cart.subtotalMinor(), tax, cart.subtotalMinor() + tax);
    }
}
