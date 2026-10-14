package com.example.checkout;

import java.util.Map;

/** The original tax engine: a flat rate per region, with a default for regions it does not list. */
public final class LegacyTaxCalculator implements TaxCalculator {

    private static final int DEFAULT_RATE_BP = 2000;
    private static final Map<String, Integer> RATES_BP = Map.of(
            "DE", 1900, "FR", 2000, "NL", 2100, "IE", 2300, "ES", 2100, "CH", 810, "GB", 2000);

    @Override
    public long taxMinor(String region, long amountMinor) {
        int rateBp = RATES_BP.getOrDefault(region, DEFAULT_RATE_BP);
        return Math.round(amountMinor * rateBp / 10_000.0);
    }
}
