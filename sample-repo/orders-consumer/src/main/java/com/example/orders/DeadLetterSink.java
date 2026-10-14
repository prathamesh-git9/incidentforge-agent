package com.example.orders;

/** Destination for records that can never be processed successfully. */
@FunctionalInterface
public interface DeadLetterSink {

    void publish(SourceRecord record, String reason);
}
