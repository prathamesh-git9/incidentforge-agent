# IncidentForge architecture

IncidentForge turns a one-sentence incident report into an evidence-backed root-cause analysis, a
mitigation recommendation, and a narrow code fix that has been validated by a real failing-then-passing
test. A human sees it all before anything leaves the system. It is a portfolio project built
around three **synthetic** incidents; it has not run against real production systems.

## Fixtures and what is per-service

The same skeleton runs three seeded incidents: a Kafka poison event (`orders-consumer`), a slow read
path (`catalog-api`) and a bad feature-flag rollout (`checkout-api`). Nothing in the stages is
specific to one of them. What varies is configuration and data:

* `incidentforge.services.<name>`: the one sample repository a patch may touch and the paths inside
  it (`editable-prefixes`, `new-file-prefixes`), the golden-signal metrics the collector fetches, the
  trace query (errors, or spans slower than N ms), and the skill domain.
* A `TelemetryWorld` per service (seeded, deterministic) behind the same MCP tools.
* `skills/<domain>/` guidance for the model stages.
* For the offline stand-in only: `OfflineScenarios`, which scripts how to locate the suspect line and
  which edit to propose. Classification, timeline and change correlation in the offline model are
  generic rules over the evidence.

## Pipeline

The plan is fixed in code (`dev.incidentforge.domain.Stage`). Models fill in judgement inside a step,
but they never choose the next step.

```
POST /api/incidents (sparse report)                                      [REPORTER]
   │  incident + run + first command written in ONE transaction (outbox)
   ▼
COLLECT_CONTEXT   deterministic: MCP tools -> page logs -> prune/fingerprint -> metric onset
   │              -> deploys/flags -> redact -> flag injections -> evidence timeline E1..En
CLASSIFY          model, small budget: category + "is a code change warranted?" (cites evidence)
   │
INVESTIGATE       3 investigators in parallel, each with a pruned evidence slice + narrow tools:
   │                timeline | change-correlation | code-path (code_search/code_read over MCP)
   │              deterministic consolidation: reject uncited claims, verify suspect file:line
PLAN_MITIGATION   deterministic: rank deploys/flags before onset (bonus if they touch failing frames)
   │              -> recommendation only, never executed
   │              non-code root cause -> stop here (NEEDS_HUMAN with RCA + mitigation)
PROPOSE_FIX       model, larger budget: structured search/replace edits + one reproduction test
   │              PatchPolicy: SECURITY violation -> stop; QUALITY violation -> bounded retry w/ feedback
VALIDATE          scratch copy: repro test alone must FAIL, then patch + full suite must PASS
   │              failure -> back to PROPOSE_FIX with Maven output (bounded) -> else NEEDS_HUMAN
AWAITING_APPROVAL reviewer sees evidence, RCA, mitigation, diff, validation, tool/model audit
   │   ├─ correction  -> PROPOSE_FIX with the reviewer's note (bounded rounds) -> re-validated
   │   ├─ reject      -> REJECTED
   │   └─ approve(sha256 of the exact diff)                                  [REVIEWER]
PUBLISH           re-render diff, refuse if hash != approved; draft PR (GitHub) or local bundle
                  idempotent branch incidentforge/<runId>; lost response -> reconcile by head branch
```

### Stage budgets (application.yml → `incidentforge.budgets`)

| Stage | Default model (when `llm.provider=anthropic`) | Effort | Max turns | Tools |
|---|---|---|---|---|
| classify | `claude-sonnet-5-5` | low | 2 | none |
| investigate (each of 3) | `claude-sonnet-5-5` | medium | 4 | per investigator: none / `deploys_list` / `code_search`,`code_read` |
| fix | `claude-opus-5-5` | high | 6 | `code_search`, `code_read` |

The investigation stage mostly gathers and summarizes data, so it gets a cheaper model and few turns.
The fix stage gets the stronger model. A turn cap is a guardrail: an actor that hasn't converged
within its budget stops with `BUDGET_EXHAUSTED` and the run goes to a human. Every reply is parsed and
JSON-Schema-validated in code (`src/main/resources/schemas`); invalid output gets one corrective
message per turn, still inside the budget.

### State passed between stages

Each stage reads explicit, durable state — run record, incident report, evidence timeline, reviewer
events — and returns the next state (`StageHandler.Outcome`). Stages never write the database.
`RunCommandProcessor` commits the new run state, new evidence, timeline notes, the processed-command
marker and the next command in **one transaction**, guarded by an optimistic `version` check.

## Evidence model

`EvidenceItem` = id (`E1`… in chronological order), kind (METRIC, LOG_SIGNATURE, STACK_TRACE, TRACE,
DEPLOY, FEATURE_FLAG, CONTEXT_STATS), source tool, **MCP tool-call id**, observed-at, **SHA-256 of the
raw tool payload**, a pruned and redacted summary, and flags (`REDACTED`, `PROMPT_INJECTION_SUSPECTED`,
`TRUNCATED`). Any number in an RCA claim can be traced to the tool call that produced it
(`GET /api/runs/{id}` → `toolCalls[]`).

On the poison-event fixture the collector makes 19 MCP calls. It pages 27,361 log lines (≈3.1 MB) in
14 pages and reduces them to 8 log signatures plus one stack trace. The whole timeline is about
16 items and under 9 KB, before any model sees it. The catalog fixture reduces 8,585 lines (702 KiB)
to 8 signatures with 10 MCP calls.

A metric that moved but did not stay anomalous is kept as a "no sustained anomaly … (since recovered)"
item rather than dropped, because it is evidence about what is *not* the cause. Example: the cache hit
ratio in the catalog fixture. Trace evidence lists every span attribute for the group (a constant
value, a numeric range, or up to three distinct values). That is how `db.rows_returned`, `code.lineno`
or a flag variant reach the investigators.

## Asynchronous job progression

* **Transactional outbox** (`command_outbox`): the next command is written in the same DB
  transaction as the state change, and `OutboxRelay` publishes it. A crash between the commit and the
  send just re-sends later.
* **Kafka** (`incidentforge.run-commands`, 3 partitions): the key is the run id, so commands for one
  run stay ordered. The producer is idempotent with `acks=all`; consumer offsets are committed per
  record after processing.
* **Redelivery safety**: each command has a durable UUID. `processed_commands` (with the id as
  primary key) is written in the same transaction as the state change. Duplicates are acknowledged
  without effect. A command whose `stage`/`expectedVersion` no longer matches the run is treated as
  stale and ignored.
* **Bounded retries and poison classification**: model overload, rate limits and MCP transport errors
  are *transient* (`FixedBackOff`, 2 retries, then DLT). Malformed commands are *poison*: they are not
  retried and go to `incidentforge.run-commands.DLT` at once. Deterministic failures (bad model output,
  policy violations, IO errors) are not retried at all; the run goes to `NEEDS_HUMAN` with the reason.
* A dead-lettered command fails its run **only** if it parses as a valid command that is still the
  run's current step. An unparseable message that happens to contain a run id cannot change that run
  (a test caught an earlier version that allowed this).
* `bus=in-memory` runs the same outbox, processor and failure classification without a broker. The
  offline demo uses it.

## MCP

`/mcp` is a stateless Streamable-HTTP MCP server built on the official MCP Java SDK (`mcp-core` 2.0.1
plus the Jackson 2 module). It exposes 7 read-only tools defined in `src/main/resources/tools/catalog.json`,
all annotated `readOnlyHint=true` and backed by the service's synthetic telemetry world and a
sandboxed repository reader. There is no flag, deploy or repository write tool, so a mitigation such
as "disable this flag" can only ever be a recommendation. The agent calls these tools through a real MCP client (`McpToolClient`) using its
own `TOOLS` credential. Arguments are validated against the same JSON Schemas on both sides of the
boundary.

## Observability

* Micrometer metrics at `/actuator/prometheus`, all low-cardinality: `incidentforge_commands_total{outcome}`,
  `incidentforge_stage_duration_seconds{stage,outcome}`, `incidentforge_tool_calls_total{tool,decision}`,
  `incidentforge_policy_denials_total{type}`, `incidentforge_model_tokens_total{stage,direction}`,
  `incidentforge_model_turns`, `incidentforge_context_bytes{phase}`, `incidentforge_publish_total{outcome}`,
  `incidentforge_evidence_injection_flags_total`.
* Micrometer Observation spans around each stage (`incidentforge.stage`), tool call
  (`incidentforge.tool.call`) and model call (`incidentforge.model.call`). Kafka template and listener
  observation is enabled, so trace context crosses the broker. Log lines carry `trace`, `span`, `run`
  and `stage`. No span exporter is configured by default (see limitations).
* A per-run audit trail is available through the API: pipeline timeline, every tool call (allowed or
  denied, argument hash, result hash, latency) and every model call (prompt hash, sizes, tokens,
  outcome). Prompts and payloads are never logged.

## Engineering lessons taken from the source talk

Source: Kriti Dangi's talk on Uber's Debug Assist, <https://www.youtube.com/watch?v=CacTDGs-If4>.
I used the talk only as design input. IncidentForge does not reuse its product names, internal
tools, examples, scale or reported metrics, and its scenario is a different domain (a backend Kafka
consumer, not a mobile app).

| Lesson from the talk (paraphrased) | How IncidentForge applies it (smaller, single-service) |
|---|---|
| A user report is a sentence; the value comes from metadata and logs captured alongside it. | Intake takes one sentence plus a few metadata fields; everything else is collected. |
| Give the agent the plan instead of letting it plan; mix deterministic and model nodes. | `Stage` enum + `RunCommandProcessor`; models act only inside a stage. |
| Context collection should be deterministic: fetching known APIs needs no model, and doing it up front avoids latency, context bloat and cost. Huge logs must be pre-processed and pruned. | `CollectContextStage` + `LogPruner` run before any model call (3 MB → <9 KB). |
| Classify first: is this even our code, or a library, infrastructure or network problem? Is a PR the right outcome? Attach a confidence. | `ClassifyStage` cites evidence ids; non-code causes stop before any fix is attempted. |
| Fetch extra observability data through MCP connections. | All telemetry and code access goes through the MCP server/client pair. |
| Fan out focused sub-investigations in parallel (e.g. reconstruct the user timeline, correlate with a release) and consolidate. Control each one's *input*, e.g. narrow the history it searches. | Three investigators on virtual threads, each with a pruned evidence slice and tool scope; consolidation is deterministic code. |
| A cheaper model with few turns for RCA, a stronger model with more turns for fixing; many turns means the agent is lost. | Per-stage budgets above; `BUDGET_EXHAUSTED` → human. |
| Pass state between nodes so nothing is forgotten. | Durable run state + evidence passed explicitly into every stage. |
| Mitigate (e.g. roll back a flag) as its own step, then fix properly. | `PLAN_MITIGATION` recommends; it never executes. |
| Validate the fix by reproducing the problem along the shortest path (unit test before component/E2E), with bounded retries. | Reproduction test must fail on the old code and pass with the fix; bounded fix attempts. |
| Show an evidence timeline so reviewers can see the agent isn't hallucinating. | Evidence timeline with tool-call provenance in the API, review page and PR body. |
| Keep the skeleton fixed; put domain knowledge in pluggable skills loaded per agent type so the context doesn't explode. | `skills/<domain>/<stage>.md`, loaded only for the incident's domain. |
| Trace every LLM and tool call. | Observations + audit tables + metrics. |
| Engineers must be able to inspect, correct and take over. | Corrections loop back to the fixer; `GET /api/runs/{id}/patch` gives a `git apply`-able diff. |

Choices made here that the talk does not prescribe: approval bound to a patch hash, a transactional
outbox with a dedupe ledger, a strict patch allowlist, and a GitHub client with no merge capability.
These follow from this project's requirements, not from the talk.
