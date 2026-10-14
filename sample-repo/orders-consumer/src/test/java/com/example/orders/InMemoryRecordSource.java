package com.example.orders;

import java.util.ArrayList;
import java.util.List;

/** Test double with Kafka-like poll/commit/seek semantics for a single partition. */
final class InMemoryRecordSource implements RecordSource {

    private final List<SourceRecord> records = new ArrayList<>();
    private long position;
    private long committedOffset;

    InMemoryRecordSource(List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            records.add(new SourceRecord(0, i, values.get(i)));
        }
    }

    @Override
    public List<SourceRecord> poll(int maxRecords) {
        int from = (int) Math.min(position, records.size());
        int to = Math.min(records.size(), from + maxRecords);
        position = to;
        return List.copyOf(records.subList(from, to));
    }

    @Override
    public void commit(long nextOffset) {
        committedOffset = nextOffset;
    }

    @Override
    public void seek(long offset) {
        position = offset;
    }

    long committedOffset() {
        return committedOffset;
    }
}
