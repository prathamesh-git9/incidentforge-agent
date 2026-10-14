package com.example.orders;

import java.util.List;

/** Minimal view of a partition consumer (mirrors the Kafka consumer poll/commit/seek contract). */
public interface RecordSource {

    /** Returns up to {@code maxRecords} records starting at the current position. */
    List<SourceRecord> poll(int maxRecords);

    /** Commits {@code nextOffset} as the next offset to read for this partition. */
    void commit(long nextOffset);

    /** Moves the read position so the next poll starts at {@code offset}. */
    void seek(long offset);
}
