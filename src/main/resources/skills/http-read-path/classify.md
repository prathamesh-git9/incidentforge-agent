Domain: synchronous HTTP read paths (request -> service -> database).

- A latency jump on one route while sibling routes stay flat points at that route's code path,
  not at shared infrastructure (database host, network, GC).
- Slow spans that carry a database statement and a large row count, inside the service's own
  code, are an APPLICATION_CODE problem even when no exception is thrown.
- A cache hit-ratio dip right after a restart is expected; it is only causal if the slow
  requests are cache misses and latency recovers when the ratio does.
