#!/usr/bin/env bash
# deploy/sample-data/check-baselines.sh — the SHOWCASE PIPELINE baseline guard (070).
#
# check-published.sh proves the published ARTIFACT still matches the repo. This
# proves the next thing along: that the seeded example pipelines still produce the
# RESULT they produced when they were written. A template edit, a dialect quirk, a
# rebuilt sample artifact or a change to the executor can all keep every test green
# and quietly change a number the marketing page quotes.
#
#   ./deploy/sample-data/check-baselines.sh <base-url> <api-key>
#
# The key needs `execute` (author works). The workspace it is pinned to must be one
# whose examples were seeded — a fresh personal workspace is exactly that.
#
# NOT a gate task, same philosophy as verify.sh and selftest.sh: it needs a running
# demo stack with the real sample data. It is a rehearsal step (deployment.md
# Appendix B) and the proof to re-run after touching content/examples.json.
#
# Exit 0 when every pipeline in expected-results-nyc.json matched; 1 otherwise, with
# the mismatching pipeline named and both checksums printed.

set -euo pipefail

BASE_URL="${1:-${DP_BASE_URL:-}}"
API_KEY="${2:-${DP_API_KEY:-}}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXPECTED="$SCRIPT_DIR/expected-results-nyc.json"

die() { echo "check-baselines: $*" >&2; exit 2; }

[ -n "$BASE_URL" ] || die "no base URL.
  usage: ./deploy/sample-data/check-baselines.sh <base-url> <api-key>
  e.g.:  ./deploy/sample-data/check-baselines.sh http://localhost:8080 dpk_..."
[ -n "$API_KEY" ] || die "no API key (argument 2, or DP_API_KEY)."
[ -f "$EXPECTED" ] || die "missing $EXPECTED"
command -v curl >/dev/null || die "curl is required"
command -v python3 >/dev/null || die "python3 is required"

BASE_URL="${BASE_URL%/}" API_KEY="$API_KEY" EXPECTED="$EXPECTED" python3 - <<'PY'
import hashlib, json, os, subprocess, sys, time

base, key, expected_path = os.environ["BASE_URL"], os.environ["API_KEY"], os.environ["EXPECTED"]
expected = json.load(open(expected_path))["pipelines"]

def call(args, timeout=600):
    # The key travels in a header, never on the command line of a second process.
    r = subprocess.run(["curl", "-sS", "--fail-with-body", "-H", f"DP-API-Key: {key}"] + args,
                       capture_output=True, text=True, timeout=timeout)
    if r.returncode != 0:
        raise SystemExit(f"check-baselines: curl failed ({r.returncode}): {r.stderr.strip()[:300]}")
    return r.stdout

try:
    listing = json.loads(call([f"{base}/api/v1/pipelines?limit=200"]))["data"]["items"]
except (KeyError, ValueError) as e:
    raise SystemExit(f"check-baselines: could not read the pipeline list from {base}: {e}")
ids = {p["name"]: p["id"] for p in listing}

failures, checked = [], 0
for name, want in expected.items():
    pid = ids.get(name)
    if pid is None:
        failures.append(f"{name}: not present in this workspace — was it seeded?")
        continue
    started = time.time()
    stream = call(["-X", "POST", "-H", "Accept: text/event-stream",
                   "-H", "Content-Type: application/json", "-d", "{}",
                   f"{base}/api/v1/pipelines/{pid}/execute"])
    elapsed = time.time() - started
    execution_id, status = None, None
    for line in stream.splitlines():
        if not line.startswith("data:"):
            continue
        try:
            event = json.loads(line[5:].strip())
        except ValueError:
            continue
        execution_id = event.get("execution_id") or execution_id
        if event.get("status") in ("SUCCESS", "FAILED", "ABORTED"):
            status = event["status"]
    if status != "SUCCESS":
        failures.append(f"{name}: execution ended {status or 'with no terminal event'}")
        continue
    result = json.loads(call([f"{base}/api/v1/executions/{execution_id}/result?limit=1000"]))["data"]
    rows = result.get("rows", [])
    got_count = result.get("total_rows", len(rows))
    # ONE canonical encoding on both sides — the recorded hash was produced by this
    # exact expression, so neither database collation nor float formatting can make
    # two equal results disagree (the two-collations trap).
    got_sha = hashlib.sha256(
        json.dumps(rows, sort_keys=True, separators=(",", ":"), default=str).encode()
    ).hexdigest()
    checked += 1
    if got_count != want["row_count"] or got_sha != want["row_sha256"]:
        failures.append(
            f"{name}: rows {got_count} (expected {want['row_count']}), "
            f"sha256 {got_sha[:16]}… (expected {want['row_sha256'][:16]}…)")
    else:
        print(f"  OK   {name:34s} rows={got_count:<5d} {elapsed:5.1f}s")

# Non-vacuity: an empty or unreadable expectations file must fail, not pass silently.
if checked == 0 and not failures:
    raise SystemExit("check-baselines: nothing was checked — the expectations file is empty")

if failures:
    print("\ncheck-baselines: FAILED")
    for f in failures:
        print(f"  {f}")
    sys.exit(1)
print(f"\ncheck-baselines: {checked} pipelines matched their recorded baseline")
PY
