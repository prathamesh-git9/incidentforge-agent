# IncidentForge engineering stories

These are notes on work demonstrated by this repository. The incident, log stream, credentials, and adversarial model are synthetic. The service has been exercised locally; none of these cases represents a customer outage or a production deployment of IncidentForge.

## The retry that could never succeed

The seeded report says the orders backlog is growing and one consumer is hot. The event at partition 3, offset 918274 contains `amount=12,50`; the sample parser rejects the comma. The warning says `rewinding for retry`, and `OrderEventProcessor` calls `source.seek(record.offset())` in the malformed-event catch block. The same offset returns, so retrying does not create progress. A prior inventory timeout and an earlier feature-flag ramp are deliberate distractors; the repeated warning, stack, lag, and source line point to the consumer.

The proposed change dead-letters that permanently malformed event and commits the next offset. The added reproduction test puts a good event after the bad one. It fails against the original code and passes with the patch, checking the committed offset, two applied good events, and one dead-lettered event. The rollback is a recommendation only; the agent does not execute it.

Useful follow-ups: why an offset must advance only after the durable dead-letter handoff; how to distinguish a transient downstream failure from a permanently malformed payload; why a cache or a larger consumer pool would not fix this partition.

## A model asked for an unsafe tool

The payload includes instruction-like text. A local test replaces the normal scripted model with a compromised one that asks for a GitHub merge tool and `code_read` on `../../../../.ssh/id_rsa`. The gateway rejects the unknown tool, the out-of-scope path, and an out-of-scope logs call. The same model proposes editing `.github/workflows/ci.yml` to skip verification; patch policy rejects it. The run ends at `NEEDS_HUMAN` with no patch, validation, or PR bundle.

The engineering decision is in the Java gateway and patch policy: model output is a request, not authority. The MCP tools exposed for investigation are read-only. The test checks denials and absence of publication, rather than relying on a prompt instruction as proof of safety.

Useful follow-ups: tool schema versus authorization; traversal and repository allowlists; why source files and logs are untrusted even if retrieved through an internal tool.

## Replay after a stage has already completed

The embedded-Kafka test runs the pipeline to `AWAITING_APPROVAL`, then republishes every command already generated for that run. It also sends malformed JSON and an invalid command using the same run key. The test checks that the processed-command count and run version do not move, the bad commands are dead-lettered, and the valid approval still reaches `PUBLISHED`.

The service persists command IDs and stage state so an at-least-once delivery does not repeat the agent work. This is distinct from the *sample consumer's* stuck offset: one story concerns the incident being diagnosed; this one concerns IncidentForge's own orchestration under redelivery.

Useful follow-ups: crash after database commit but before Kafka offset acknowledgment; why a run ID is the Kafka key; why exactly-once broker semantics do not make a GitHub write exactly once.

## The patch is reviewable, not self-authorizing

The end-to-end test submits the same incident twice with one idempotency key and gets the same run. At the review boundary, a reporter receives 403 when trying to approve; a reviewer with the wrong patch hash receives 409. A scripted correction asks the fixer to keep the WARN line for dashboards; that causes another fix/validation round before approval. The approved hash appears in the local draft PR bundle, and `git apply --check` succeeds against the clean sample repo.

The scripted correction is a test input, not a teammate conversation. The test validates a specific approval boundary; the hosted GitHub publisher is tested against a local fake API, not github.com.

Useful follow-ups: what to do after an uncertain GitHub response; why approval binds to a hash rather than a run ID alone; why a passing test still needs human review.
