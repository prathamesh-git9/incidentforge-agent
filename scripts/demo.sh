#!/usr/bin/env bash
# Credential-free demo of the full IncidentForge path (offline model, in-memory bus, local PR bundle).
# Requires: Java 21+, Maven, curl, python3 (only for pretty-printing JSON).
# FIXTURE picks the seeded incident (default: the Kafka poison event):
#   FIXTURE=catalog-slow-product-page.json bash scripts/demo.sh   # slow read path
#   FIXTURE=checkout-quote-errors.json     bash scripts/demo.sh   # bad feature-flag rollout
set -euo pipefail

cd "$(dirname "$0")/.."
PORT="${PORT:-8080}"
BASE="http://localhost:${PORT}"
REPORTER="reporter:demo-reporter-only"
REVIEWER="reviewer:demo-reviewer-only"
PY="$(command -v python3 || command -v python)"
FIXTURE="${FIXTURE:-orders-consumer-lag.json}"

json() { "$PY" -c "import sys, json; d = json.load(sys.stdin); print($1)"; }

if [ ! -f target/incidentforge-agent.jar ]; then
  mvn -B -q -DskipTests package
fi

java -jar target/incidentforge-agent.jar --spring.profiles.active=offline --server.port="$PORT" > target/demo-app.log 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true' EXIT

echo "==> waiting for the app on $BASE"
for _ in $(seq 1 60); do curl -fs "$BASE/actuator/health" >/dev/null 2>&1 && break; sleep 1; done

echo "==> filing the sparse incident report (samples/incidents/$FIXTURE)"
RUN_ID=$(curl -fs -u "$REPORTER" -H 'Content-Type: application/json' -H "Idempotency-Key: demo-$(date +%s)" \
  -d @"samples/incidents/$FIXTURE" "$BASE/api/incidents" | json "d['runId']")
echo "    run: $RUN_ID"

echo "==> pipeline running (collect -> classify -> investigate -> mitigate -> fix -> validate)"
STATUS=IN_PROGRESS
while [ "$STATUS" = "IN_PROGRESS" ]; do
  sleep 2
  STATUS=$(curl -fs -u "$REVIEWER" "$BASE/api/runs/$RUN_ID" | json "d['status']")
done

curl -fs -u "$REVIEWER" "$BASE/api/runs/$RUN_ID" > target/demo-run.json
"$PY" - <<'EOF'
import json
d = json.load(open("target/demo-run.json"))
print("\nstatus:", d["status"], "-", d["statusReason"])
print("\n--- pipeline timeline")
for t in d["timeline"]:
    print(f"  [{t['stage'] or '-':15}] {t['kind']:8} {t['message'][:150]}")
print("\n--- evidence timeline (untrusted, redacted)")
for e in d["evidence"]:
    flags = ("  <" + ",".join(e["flags"]) + ">") if e["flags"] else ""
    print(f"  {e['evidenceId']:>3} {e['kind']:13} {e['summary'].splitlines()[0][:110]}{flags}")
rca = d["rootCause"]
print("\n--- root cause (confidence %s)" % rca["confidence"])
print("  ", rca["summary"])
loc = rca["suspectLocation"]
print("   suspect: %s:%s verified=%s" % (loc["path"], loc["line"], loc["verified"]))
print("   mitigation (not executed):", d["mitigation"]["action"], d["mitigation"]["target"])
print("   validation:", d["validation"]["verdict"],
      "(repro exit %s before fix, suite exit %s after)" % (d["validation"]["beforeFix"]["exitCode"],
                                                           d["validation"]["afterFix"]["exitCode"]))
print("   tool calls:", len(d["toolCalls"]), "| model calls:", len(d["modelCalls"]))
print("\n--- proposed patch, sha256", d["patchSha256"])
print(d["patch"]["unifiedDiff"])
EOF

if [ "$STATUS" != "AWAITING_APPROVAL" ]; then
  echo "Run stopped in $STATUS; see target/demo-run.json"; exit 1
fi

SHA=$("$PY" -c "import json; print(json.load(open('target/demo-run.json'))['patchSha256'])")
echo "==> a reporter cannot approve (expect 403):"
curl -s -o /dev/null -w "    HTTP %{http_code}\n" -u "$REPORTER" -H 'Content-Type: application/json' \
  -d "{\"decision\":\"APPROVE\",\"patchSha256\":\"$SHA\"}" "$BASE/api/runs/$RUN_ID/decision"

echo "==> reviewer approves exactly this patch hash"
curl -fs -u "$REVIEWER" -H 'Content-Type: application/json' \
  -d "{\"decision\":\"APPROVE\",\"patchSha256\":\"$SHA\",\"comment\":\"evidence and diff reviewed\"}" \
  "$BASE/api/runs/$RUN_ID/decision" > /dev/null
for _ in $(seq 1 30); do
  STATUS=$(curl -fs -u "$REVIEWER" "$BASE/api/runs/$RUN_ID" | json "d['status']")
  [ "$STATUS" = "PUBLISHED" ] && break; sleep 1
done
echo "    status: $STATUS"
echo "==> draft PR bundle: target/pr-drafts/$RUN_ID/"
ls "target/pr-drafts/$RUN_ID/"
echo "==> metrics sample"
curl -fs "$BASE/actuator/prometheus" | grep -E '^incidentforge_(commands|policy|tool_calls)_total' | head -8
