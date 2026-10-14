package com.example.checkout;

import java.util.Map;
import java.util.Objects;

/**
 * New tax engine, rolled out behind the {@code checkout.new-tax-engine} flag. Its rate table
 * covers the regions migrated so far; any other region must be priced by the legacy engine.
 */
public final class TaxCalculatorV2 implements TaxCalculator {

    private static final Map<String, Integer> RATES_BP = Map.of(
            "DE", 1900, "FR", 2000, "NL", 2100, "IE", 2300, "ES", 2100);

    private final TaxCalculator legacy;

    public TaxCalculatorV2(TaxCalculator legacy) {
        this.legacy = Objects.requireNonNull(legacy);
    }

    @Override
    public long taxMinor(String region, long amountMinor) {
        int rateBp = RATES_BP.get(region);
        return Math.round(amountMinor * rateBp / 10_000.0);
    }
}
