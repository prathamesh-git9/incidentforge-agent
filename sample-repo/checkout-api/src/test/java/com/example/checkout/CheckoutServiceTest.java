package com.example.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CheckoutServiceTest {

    private final LegacyTaxCalculator legacy = new LegacyTaxCalculator();
    private final TaxCalculatorV2 v2 = new TaxCalculatorV2(legacy);

    @Test
    void flagOffUsesTheLegacyEngine() {
        CheckoutService checkout = new CheckoutService((flag, subject) -> false, legacy, v2);

        assertEquals(new Quote(10_000, 810, 10_810), checkout.quote(new Cart("c-1", "CH", 10_000), "u-1"));
    }

    @Test
    void flagOnUsesTheNewEngineForMigratedRegions() {
        CheckoutService checkout = new CheckoutService((flag, subject) -> true, legacy, v2);

        assertEquals(new Quote(10_000, 1_900, 11_900), checkout.quote(new Cart("c-2", "DE", 10_000), "u-2"));
    }
}
