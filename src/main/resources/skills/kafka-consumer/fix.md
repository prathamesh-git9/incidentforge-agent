Domain: Kafka consumer services.

- The permanent fix for a poison record is to classify it as non-retryable, route it to the
  dead-letter sink with the reason, and commit past it so the partition keeps moving. Do not
  "fix" it by loosening validation or swallowing the error silently.
- Keep the change narrow: touch only the handler that loops. Do not edit build files, CI
  configuration or existing tests.
- Add one reproduction test that feeds a good record, the malformed record and another good
  record, drives a bounded number of polls, and asserts progress (committed offset, applied count,
  one dead letter). It must fail on the current code and pass with the fix.
