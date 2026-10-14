# IncidentForge Agent

IncidentForge takes a sparse production-incident report (*"orders backlog keeps growing and one consumer
pod is pinned at high CPU"*) and works through:

1. deterministic evidence collection over **MCP**;
2. classification;
3. parallel, budget-bounded investigations;
4. a mitigation recommendation;
5. a narrow code fix;
6. validation by a **real reproduction test that fails before the fix and passes after it**;
7. a **draft** pull request, published only after a human approves the exact patch hash.

Stage-to-stage progression runs over **Kafka** (or an in-memory bus with identical semantics), and
the whole path runs offline without credentials.

> **Scope, honestly:** this is a portfolio project built in October 2026 around a **seeded, synthetic
> incident** in a small sample service (`sample-repo/orders-consumer`). It has not run against real
> production systems or traffic, and the offline "model" is a scripted stand-in (see
> [Limitations](#limitations)). The design was informed by a public talk; see
> [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#engineering-lessons-taken-from-the-source-talk).

**Stack:** Java 21, Spring Boot 3.5, Spring Kafka, official MCP Java SDK 2.0 (stateless Streamable HTTP),
official Anthropic Java SDK (Claude adapter), H2/JDBC, Micrometer (metrics + observations), JUnit 5 /
embedded KRaft Kafka.

## The seeded incident

Release `1.8.3` of `orders-consumer` made amount parsing strict and changed parse failures from
"dead-letter" to "seek back and retry". At 10:41:07 an event with amount `12,50` arrives on partition 3.
It can never parse, so the consumer rewinds to the same offset about 10 times a second: lag on
partition 3 climbs to ~48k, and the pod that owns it sits at 95% CPU.

The world (`SyntheticTelemetry`, fixed seed) also contains distractors:

* an inventory-service timeout before the onset;
* a feature-flag ramp 21 minutes before the onset;
* a leaked fake GitHub token in an INFO log;
* a **prompt-injection payload** inside the poison event, telling AI agents to merge their own PR and
  disable CI.

`SeededIncidentConsistencyTest` checks that every stack frame in the telemetry points to the real
line in the sample repository.

## Quick start (offline, no credentials, no Docker)

Prerequisites: JDK 21+ and Maven 3.9+ on `PATH`. The validator shells out to `mvn`. `curl` and
`python3` are needed only for the demo script's output.

```bash
mvn -B verify               # 30 tests, including embedded Kafka and real Maven validation (~2.5 min)
bash scripts/demo.sh        # builds, starts the app offline, runs the incident, approves, publishes
```

Or step by step:

```bash
mvn -B -DskipTests package
java -jar target/incidentforge-agent.jar --spring.profiles.active=offline

# file the sparse report (REPORTER role)
curl -u reporter:demo-reporter-only -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-0001' \
     -d @samples/incidents/orders-consumer-lag.json http://localhost:8080/api/incidents
# -> {"incidentId":"inc-…","runId":"run-…","duplicate":false}

# watch it (REVIEWER or REPORTER role); also: http://localhost:8080/review.html?run=run-…
curl -u reviewer:demo-reviewer-only http://localhost:8080/api/runs/run-…

# take over locally at any time: the validated diff, ready for `git apply`
curl -u reviewer:demo-reviewer-only http://localhost:8080/api/runs/run-…/patch

# approve the exact patch hash shown in the run (REVIEWER only; a REPORTER gets 403, a stale hash 409)
curl -u reviewer:demo-reviewer-only -H 'Content-Type: application/json' \
     -d '{"decision":"APPROVE","patchSha256":"<patchSha256>","comment":"reviewed"}' \
     http://localhost:8080/api/runs/run-…/decision
# -> PUBLISHED; draft PR bundle in target/pr-drafts/run-…/ (PULL_REQUEST.md, change.patch, metadata.json)
```

The `demo-*` passwords exist only in `application-offline.yml`. The app refuses to start with them
when a real model or GitHub publishing is configured.

### Captured demo output (excerpt, `bash scripts/demo.sh`, 2026-10-13)

```text
--- pipeline timeline
  [COLLECT_CONTEXT] SECURITY E5 had credentials or personal data redacted
  [COLLECT_CONTEXT] SECURITY E9 contains instruction-like text aimed at agents; kept as untrusted evidence only
  [COLLECT_CONTEXT] INFO     collected 16 evidence items via 19 MCP tool calls; 27361 log lines (3158 KiB) reduced to 8 signatures (2628 chars)
  [CLASSIFY       ] INFO     classified as APPLICATION_CODE (confidence 0.8, code fix recommended) citing E10,E11,E14
  [INVESTIGATE    ] INFO     code-path-investigator finished in 3 turn(s) with 2 tool call(s)
  [INVESTIGATE    ] INFO     RCA confidence 0.77; suspect src/main/java/com/example/orders/OrderEventProcessor.java:49 (verified); 8 cited claims, 0 rejected
  [PLAN_MITIGATION] INFO     mitigation recommendation (not executed): ROLLBACK_DEPLOY 1.8.3
  [PROPOSE_FIX    ] INFO     patch attempt 1: 2 file(s), 42 changed lines, sha256 fc9c48b0bf2c
  [VALIDATE       ] INFO     validation REPRODUCED_THEN_FIXED: reproduction test PoisonEventReproTest failed on the current code and the full suite passed with the patch (before: exit 1 …; after: exit 0 …)

--- evidence timeline (untrusted, redacted)
   E5 LOG_SIGNATURE INFO c.e.o.HttpConfig x1 …: outbound client configured hea…  <REDACTED>
   E7 DEPLOY        deploy 1.8.3 commit 7f3c2a9: Strict decimal amount parsing (#212); retry malformed events instead of dead-lett…
   E9 LOG_SIGNATURE ERROR c.e.o.PayloadAuditLogger x8 …: failed to par…  <PROMPT_INJECTION_SUSPECTED>
  E10 LOG_SIGNATURE WARN c.e.o.OrderEventProcessor x14330 …: malformed event partition=3 offset=918274; rewinding for retry
  E14 METRIC        consumer_lag group=orders-ledger partition=3 rose from baseline ~40 to 47821 (x1196); sustained since 2026-10-12T10:42Z …
  E16 CONTEXT_STATS pruned 27361 log lines (3158 KiB) from 14 MCP pages into 8 of 8 signatures

--- proposed patch (excerpt)
             } catch (MalformedEventException e) {
-                LOG.log(Level.WARNING, "malformed event partition={0} offset={1}; rewinding for retry",
+                // A malformed record fails identically on every redelivery (poison event):
+                // dead-letter it and commit past it instead of rewinding the partition.
+                LOG.log(Level.WARNING, "malformed event partition={0} offset={1}; dead-lettering",
                         record.partition(), record.offset());
-                source.seek(record.offset());
-                return applied;
+                deadLetters.publish(record, "malformed: " + e.getMessage());
+                source.commit(record.offset() + 1);
             }
 (+ new file src/test/java/com/example/orders/PoisonEventReproTest.java)

==> a reporter cannot approve (expect 403):
    HTTP 403
==> reviewer approves exactly this patch hash
    status: PUBLISHED
```

`git apply --check change.patch` succeeds against a clean copy of the sample repository; the
end-to-end test asserts this.

## Running with Kafka (Docker Compose)

```bash
docker compose up -d kafka                     # apache/kafka:3.9.1, KRaft, localhost:9092
export INCIDENTFORGE_REPORTER_PASSWORD=… INCIDENTFORGE_REVIEWER_PASSWORD=… INCIDENTFORGE_MCP_PASSWORD=…
java -jar target/incidentforge-agent.jar       # default profile: bus=kafka, H2 file DB in ./data
# or everything in containers:
docker compose --profile app up --build
```

What I verified locally on 2026-10-13 (Docker 28.5.2):

* The jar in Kafka mode against the Compose broker: a run reached `AWAITING_APPROVAL` and then
  `PUBLISHED`. Its commands landed on one partition (run-id key), and consumer group
  `incidentforge-pipeline` finished with lag 0.
* `docker build` of the image (1.23 GB, because it bundles a JDK and Maven for validation).
* The offline flow inside that container as uid 10001, reaching `REPRODUCED_THEN_FIXED`.

I did **not** run the combined `docker compose --profile app up` stack.

## Using Claude instead of the offline stand-in

```bash
export ANTHROPIC_API_KEY=…   # workspace key with a spend limit
java -jar target/incidentforge-agent.jar --incidentforge.llm.provider=anthropic   # plus the password env vars
```

Per-stage models and budgets are in `application.yml` → `incidentforge.budgets`: `claude-sonnet-5-5`
with few turns for classification and investigation, `claude-opus-5-5` with more turns for the fix.
The adapter uses the official Anthropic Java SDK and sends the stage's `effort`. It opts into the
server-side refusal fallback (`incidentforge.llm.anthropic.refusal-fallback`, default `true`). It
classifies 429/5xx/network errors as transient (bounded stage retry) and 4xx/refusals as "stop for a
human". `AnthropicModelClientTest` verifies the request shape against a local fake of the Messages API.
**It has not been run against the real API in this repository** (no key was used).

## Publishing real draft PRs on GitHub

```bash
export INCIDENTFORGE_GITHUB_TOKEN=…   # fine-grained: ONE repo, Contents RW + Pull requests RW only
java -jar target/incidentforge-agent.jar --incidentforge.github.mode=github \
     --incidentforge.github.repository=<owner>/<sample-repo> --incidentforge.github.path-prefix=""
```

The publisher creates branch `incidentforge/<runId>`, writes only the approved files, and opens a
**draft** PR whose body includes the evidence table and validation results. It is idempotent and
reconciles lost responses. `GitHubDraftPublisherTest` exercises it against an in-process fake of the
GitHub REST API. It was **not** run against github.com from this repository.

## Connecting another MCP client

`/mcp` is a standard stateless Streamable-HTTP MCP endpoint (protocol handled by the official SDK). It
exposes seven read-only tools: `logs_search`, `metrics_query`, `traces_search`, `deploys_list`,
`flags_list`, `code_search`, `code_read`. Authenticate as the `TOOLS` user with
`Authorization: Basic base64(mcp-agent:<password>)`.

## API

| Method & path | Role | Purpose |
|---|---|---|
| `POST /api/incidents` (`Idempotency-Key` optional) | REPORTER | file a sparse report; starts a run |
| `GET /api/runs/{id}` | REVIEWER, REPORTER | status, evidence timeline, RCA, mitigation, patch, validation, tool/model audit, reviews |
| `GET /api/runs/{id}/patch` | REVIEWER, REPORTER | unified diff for `git apply` |
| `POST /api/runs/{id}/decision` `{decision: APPROVE\|REJECT, patchSha256, comment}` | REVIEWER | approval is bound to the exact diff hash |
| `POST /api/runs/{id}/corrections` `{comment}` | REVIEWER | send a correction to the fixer (re-validated, max 2 rounds) |
| `POST /api/runs/{id}/publish/retry` | REVIEWER | reconcile a `PUBLISH_UNCERTAIN` run |
| `POST /mcp` | TOOLS | MCP JSON-RPC |
| `GET /actuator/prometheus`, `/actuator/health` | public | metrics, health |

## Tests (`mvn -B verify`: 30 tests, 0 failures, ~2.5 min on the author's Windows laptop)

| Test | What it proves |
|---|---|
| `OfflineEndToEndTest` | Full slice over REST: idempotent intake; evidence provenance, redaction and injection flag; verified suspect location; cited claims only; mitigation not executed; `REPRODUCED_THEN_FIXED`; reporter can't approve (403); stale hash (409); reviewer correction re-runs the fix and validation; approval → PUBLISHED; `git apply --check` passes; no token, injection text, password or prompt fence in logs; metrics exposed. |
| `CompromisedModelTest` | **Negative:** a model obeying the injection asks for `github_merge_pull_request`, a path-traversal read and an out-of-scope tool, then proposes editing `.github/workflows/ci.yml`. Everything is denied and audited, the run stops at `NEEDS_HUMAN`, and nothing is validated or published. |
| `KafkaPipelineTest` | Same pipeline over embedded KRaft Kafka. **Negative:** every command is redelivered (no state change, ledger unchanged) and two poison messages go to the DLT without failing the run or blocking the partition. |
| `McpToolBoundaryTest` | Real MCP client discovers exactly 7 read-only tools; `/mcp` returns 401/403 to non-TOOLS callers; gateway denies unknown, out-of-stage, out-of-scope, schema-invalid and cross-service calls and audits all six; server-side limits hold without the gateway. |
| `ValidationRunnerTest` | Runs real Maven: a correct fix is `REPRODUCED_THEN_FIXED`; a non-compiling patch is `TESTS_FAILED`; a "reproduction" test that passes on buggy code is `NOT_REPRODUCED`. |
| `BoundedFailureModesTest` | A permanently overloaded model is tried 3 times, then the run is `FAILED`; a model that never finishes is stopped by its 2-turn budget. |
| `GitHubDraftPublisherTest` | Draft PR created with no merge call; a lost create response is reconciled (still one PR); redelivery reuses the PR; remote drift is a conflict; the client has no merge/delete/approve method. |
| `AnthropicModelClientTest` | SDK request shape (model, max_tokens, effort, system, replayed turns, fallback header and body); 429/529 → transient; 400 and refusal → stop. |
| `PatchPolicyTest`, `EvidenceSafetyTest`, `SeededIncidentConsistencyTest` | Allowlist and size limits, redaction, injection detection, pruning (27k lines → ≤10 signatures), telemetry ↔ code consistency. |

CI (`.github/workflows/ci.yml`) runs the sample repository's tests and then `mvn verify` on Java 21.

## Layout

```
src/main/java/dev/incidentforge/
  api/           REST intake, review service (approve / reject / correct / retry)
  bus/           outbox relay, Kafka transport + listeners + DLT, in-memory transport
  pipeline/      command model, idempotent processor, stages/ (collect … publish)
  mcp/           MCP server (official SDK) + read-only tool handlers
  tools/         tool catalog, JSON-Schema validator, MCP client, ToolGateway + ToolPolicy
  llm/           ModelClient, Claude adapter, offline scripted model, bounded agent loop, prompts, skills
  evidence/      log pruning, redaction, injection signal
  patch/         patch policy, diff rendering, scratch workspaces
  validation/    reproduce-then-fix Maven runner
  publish/       local bundle publisher, GitHub REST client + idempotent draft publisher
  telemetry/     seeded synthetic incident world
src/main/resources/  application*.yml, schema.sql, tools/catalog.json, schemas/, skills/, static/review.html
sample-repo/orders-consumer/   the only repository IncidentForge may patch (plain Java + JUnit)
samples/incidents/             the sparse report used by the demo and tests
docs/ARCHITECTURE.md, docs/TRUST-BOUNDARIES.md
```

## Limitations

* **Offline model is scripted.** `OfflineModelClient` is a deterministic heuristic written for this
  seeded incident. It exercises the real loop, MCP tools, schemas, budgets and policies, but says
  nothing about model reasoning quality. With `provider=anthropic`, results will vary, and the
  prompts have not been evaluated against the real API.
* **One synthetic service and one incident.** The telemetry tools serve a generated world, not a real
  log, metrics or tracing backend. Adding a real backend means implementing the same MCP tool
  contracts.
* **The validator is not a sandbox.** Maven runs as a host child process with a timeout and scrubbed
  env. Production use needs an ephemeral, network-less container.
* **The real GitHub and Claude paths are tested only against local fakes.** Neither was exercised
  against the live services here.
* **Tracing** creates spans and propagates trace context through Kafka, but no exporter
  (OTLP/Zipkin) is configured. Trace ids appear in logs, and the per-run audit trail is served by the
  API.
* **Mitigation is recommendation-only** by design. There is no flag or deploy write path.
* **Single-node assumptions.** H2, in-memory users and an `@Scheduled` outbox relay are fine for a
  demo. Multiple app instances would need a shared database (Postgres), outbox row locking or a CDC
  relay, and SSO.
* The runtime image is large (1.23 GB), because validation needs a JDK and Maven inside it.
