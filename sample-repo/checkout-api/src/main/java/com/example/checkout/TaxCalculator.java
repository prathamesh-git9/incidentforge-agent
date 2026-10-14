package com.example.checkout;

public interface TaxCalculator {

    /** Tax in minor units for {@code amountMinor} in {@code region} (ISO 3166 alpha-2). */
    long taxMinor(String region, long amountMinor);
}
