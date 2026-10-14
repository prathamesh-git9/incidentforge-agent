Domain: Kafka consumer services.

- Map stack frames to files with code_search/code_read before naming a suspect line; cite the
  stack-trace evidence id for every code claim.
- A record that fails deterministically (parse/validation error) fails identically on every
  redelivery. Any handler that seeks back, rethrows without a retry limit, or skips the commit for
  such a record creates a hot loop: high CPU, zero progress, growing lag.
- Correlate the onset with the closest preceding deploy or flag change, and prefer changes whose
  changed files intersect the stack frames.
