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

## GitHub created the PR, but the response was lost

`GitHubDraftPublisherTest` runs the publisher against an in-process fake GitHub API. One variant accepts a draft PR request and creates the PR, but holds the response long enough for the client to time out. A blind retry could open a second PR. The publisher queries for its stable run branch, finds the existing draft and records the result. Repeating the publish call leaves exactly one PR; the test also checks that the file writes were not repeated. Another variant changes the target file on the remote default branch after local validation and confirms the publisher refuses to publish over that drift.

This is a local protocol failure test, not a claim that github.com timed out in production. It gives a concrete answer to “what do you do after an uncertain external write?” without pretending Kafka transactions cover GitHub.

Useful follow-ups: stable branch/PR identifiers; distinguishing a lost response from a rejected write; when to stop and require a person because reconciliation is inconclusive.

## The model never finishes its turn

`BoundedFailureModesTest` substitutes a classifier that repeatedly asks to call `logs_search`, which that stage is not allowed to use. The gateway denies the call, and the classifier keeps asking. The run stops at `NEEDS_HUMAN` after its two-turn budget rather than consuming unbounded time and tokens. A separate variant makes the provider throw a simulated `529 overloaded` on every attempt; the run records `FAILED` after three total attempts.

The point is the separation between a transient provider error, which gets a small bounded retry, and a model loop that violates the stage contract, which stops for a person. Both cases are deterministic local tests. The budget itself is not a performance or production-reliability claim.


## Three fixtures, one pipeline

The six stories above all run on the original fixture: a Kafka poison event in `orders-consumer`. Two more seeded incidents now go through the same stages, tools, budgets, validator and approval boundary. Each fixture has its own sample repository under `sample-repo/`, its own telemetry world under `dev.incidentforge.telemetry`, its own report under `samples/incidents/`, and its own end-to-end test. They were chosen to need different evidence and different decisions, not to restate the poison event:

| Fixture | Symptom in the report | Deciding evidence | Mitigation (recommended only) | Permanent fix | How the fix is proven |
|---|---|---|---|---|---|
| `orders-consumer-lag.json` | backlog growing, one pod hot | one partition's lag + 14k identical "rewinding" warnings + stack | roll back 1.8.3 | dead-letter the malformed event and commit past it | repro test: committed offset advances past the bad record |
| `catalog-slow-product-page.json` | best-seller pages take seconds | one route's p95 + slow spans with `db.rows_returned` in the tens of thousands + slow-query log | roll back 2.4.0 | use the existing aggregate projection and indexed top-N query | repro test: review rows read per page view (no timing) |
| `checkout-quote-errors.json` | some customers get an error at checkout | error rate up for region CH only, same version everywhere + flag ramp whose code refs match the failing frame | **disable the flag** (not a rollback) | restore the legacy-engine fallback for regions outside the new tax table | repro test: flag-on quote for CH succeeds; migrated regions keep new rates |

To support this I made a few generic changes rather than special-casing the new incidents:

* The collector reads per-service golden signals and trace queries from config (`incidentforge.services.*.metrics` and `.traces`).
* Patch path allowlists moved from one global list to each service. `PatchPolicyTest` checks that an orders path is a SECURITY violation for a `catalog-api` run.
* Flags carry code references.
* Trace evidence lists every span attribute.
* A metric that moved and came back is now recorded as "no sustained anomaly" evidence instead of being dropped.

In the offline stand-in model, classification, timeline and change correlation became generic rules over the evidence. Only locating the suspect line and choosing the edit remain per-fixture scripts, and those are isolated in `OfflineScenarios` so it is clear what is scripted.

## The page was slow, and the cache looked guilty

The report says best-seller product pages have been slow since the morning; no exception is logged anywhere.

* `http_latency_p95_ms` for `/products/{id}` rose about 21x at 09:31, while `/search` stayed flat.
* The 2.4.0 restart at 09:30 emptied the product cache. The cache hit ratio dropped to 0.55 and 19 "cache miss ratio" warnings followed. That is the obvious suspect, and "add or warm a cache" is the obvious fix.
* The evidence argues against it. The cache metric is recorded as *no sustained anomaly … (since recovered)*: it was back to about 0.94 by 09:50, while p95 stayed above 2.3 s. The slow spans all carry `cache.product_hit=true`, so the cache is not on the slow path.
* The same spans carry `db.statement=SELECT * FROM reviews WHERE product_id = ?`, `db.rows_returned=18877..41230` and `code.lineno=24`.

The code-path investigator reads `ProductDetailService.java` at that line and finds the 2.4.0 change. It loads every review row to compute a count, an average and the top three. The change investigator ties the onset to deploy 2.4.0 (it touches that file) and rules out the search-ranking flag ramped 21 minutes earlier (its code refs point at `SearchRanker.java`).

The fix uses two repository methods that already existed for other features: `statsFor` (an aggregate) and `topByHelpfulness` (indexed top-N). The page content is unchanged, and no cache is added.

The reproduction test deliberately does not assert on time. A timing assertion would be flaky and machine-dependent. Instead, `FakeCatalog` counts review rows materialized, the way a JDBC driver would. With 10,000 reviews, the shipped code reads 10,000 rows and fails the `rowsRead() <= 4` assertion; the fix reads 4 and passes. The same test checks count, average and the top review, so the cheaper query cannot silently change the page.

`SlowReadPathIncidentTest` asserts all of this: the route-specific anomaly, the recovered cache metric, the trace attributes, the verified suspect `ProductDetailService.java:24`, the cache and the search flag ruled out with citations, rollback recommended but not executed, a diff with no cache code, and `REPRODUCED_THEN_FIXED`. In the local smoke run the collector reduced 8,585 log lines (702 KiB) to 8 signatures with 10 MCP calls.

Useful follow-ups: why a cache would hide the cold path (first view, evictions, the long tail of products); why rows-read is a better regression test than milliseconds; when you would still roll back instead of shipping the query fix.

## Turn the flag off, then fix the code

The report says some customers get an error at checkout since about 14:00. Nothing was deployed: the only release in the window, 3.2.1, is three days old and only touched the payment client. What changed was the flag `checkout.new-tax-engine`, ramped from 0% to 25% at 14:02.

* `error_rate_percent` for region CH rose about 130x, while DE and FR stayed flat. All three series carry `version=3.2.1`.
* The failing spans carry `checkout.region=CH`, `feature_flag.checkout.new-tax-engine=true` and a NullPointerException at `TaxCalculatorV2.java:23`.
* The flag's code refs list `TaxCalculatorV2.java`.

The distractors are ruled out with citations:

* payment-gateway latency warnings are just as frequent before the onset as after it, and the latency metric has no sustained anomaly;
* the express-pay flag changed 33 minutes before the onset and its code refs point elsewhere;
* the 3.2.1 deploy does not touch the failing code.

The decisions differ from the other two fixtures. The mitigation planner chooses `DISABLE_FEATURE_FLAG checkout.new-tax-engine`, not a rollback, because there is nothing to roll back. It is recorded as a recommendation with `executed=false`, and the risk text says IncidentForge has no flag write access. The test checks that the MCP catalog has no tool that could set, toggle or write anything.

The permanent fix is separate. `TaxCalculatorV2` documents that regions outside its table must be priced by the legacy engine, and it even receives the legacy calculator, but it unboxes `RATES_BP.get(region)` with no fallback. The fix restores the fallback inside the flagged path; the flag-off path is untouched. The reproduction test runs the flag-on path for a CH cart, which throws on the shipped code and quotes 10,810 with the fix. A second case checks that a migrated region (NL) keeps the new engine's rate.

`FeatureRolloutIncidentTest` also approves the exact patch hash and checks the published PR bundle. Its body lists the flag mitigation under "recommended separately, not executed".

Useful follow-ups: why disabling the flag and fixing the code are two decisions; who should flip a production flag and why not the agent; how you would know it is safe to re-enable the flag (the reproduction test for the failing region, plus the unchanged migrated-region case).

## "Maven is not on PATH" — except it was

The checkout fixture was the first whose reproduction test *errors* (NullPointerException) instead of *failing* an assertion. Its first local run stopped at `NEEDS_HUMAN` with "Could not run Maven for validation (is it on PATH?)", while the catalog fixture's Maven validation had just passed in the same process.

The log explained it. For a test error, Surefire prints `Test.method:16 » NullPointer …`. On Windows the forked JVM wrote `»` in the console code page as the single byte `0xBB`. The validator read the output strictly as UTF-8, got a `MalformedInputException`, and its single `catch (IOException)` labelled that as a missing Maven.

The fix has two parts:

* Decode child output leniently.
* Split launch failures ("could not start Maven") from output-reading failures, so the two can no longer be confused.

`ValidationRunnerTest` now writes a log containing the `0xBB` byte and checks it is read. The checkout end-to-end test checks that the before-fix output contains `TaxEngineFallbackReproTest` and `NullPointer`.

This happened on the author's Windows machine during local development; a Linux CI runner writes UTF-8 and would not have shown it.

Useful follow-ups: why one catch block for "start the process" and "read its output" was the real bug; why an error message that suggests a fix ("is it on PATH?") is dangerous when it is wrong.

## The skip-offset alternative named the wrong partition

The poison-event fixture lists "skip the poison offset" as an alternative to rolling back. When I printed the alternatives while adding the new fixtures, it said *partition 4 offset 505660*. The poison record is at partition 3, offset 918274.

The regex that extracts `partition=N offset=N` scanned every log signature, and the first one in the timeline was an INFO "applied order event" line from a healthy partition. The alternative was never executed, but a person acting on it would have skipped a good event and left the hot loop running.

The fix only considers WARN and ERROR signatures. `OfflineEndToEndTest` now asserts the alternative names partition 3, offset 918274. The original test had not checked the alternatives at all, which is how this survived the first version.

## Observed local results for this update

`mvn -B verify` on the author's Windows laptop (JDK 23 compiling with `--release 21`, Maven 3.9.9): **36 tests, 0 failures**, 2 min 52 s (it was 30 tests before this update). The three sample repositories' own test suites pass with `mvn test`. All three fixtures were also run against the packaged jar (`java -jar … --spring.profiles.active=offline`), and each reached `AWAITING_APPROVAL` with `REPRODUCED_THEN_FIXED`. These numbers describe a local run against synthetic data; they say nothing about production behaviour.
