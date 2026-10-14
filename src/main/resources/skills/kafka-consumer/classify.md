Domain: Kafka consumer services.

- Growing lag on one partition while sibling partitions stay flat points at that partition's
  consumer making no progress, not at broker capacity. Cluster-wide lag points at the broker,
  network or a shared dependency.
- A WARN/ERROR signature repeating thousands of times for the same partition and offset means the
  same record is being reprocessed: classify as APPLICATION_CODE when the stack frames are in the
  service's own packages.
- Timeouts to other services that stop before the symptom started are usually unrelated noise.
- Recommend a code fix only when the evidence ties the symptom to code in this service.
