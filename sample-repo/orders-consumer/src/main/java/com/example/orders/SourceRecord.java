package com.example.orders;

/** A record read from one partition of the orders topic. A null value is a tombstone. */
public record SourceRecord(int partition, long offset, String value) {
}
