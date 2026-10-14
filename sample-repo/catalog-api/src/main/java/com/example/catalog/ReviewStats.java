package com.example.catalog;

/** Aggregate review figures for one product, computed by the database. */
public record ReviewStats(long count, double averageStars) {
}
