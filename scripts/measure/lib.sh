#!/usr/bin/env bash
# Shared preamble for every measurement script — the discipline, in one place.
set -uo pipefail

measure_header() {
  local name="$1"
  echo "=============================================================================="
  echo "measurement: $name"
  echo "tree:        $(git -C "$(dirname "${BASH_SOURCE[0]}")/../.." rev-parse HEAD)"
  echo "when:        $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "load:        $(uptime | sed 's/.*load averages*: *//')"
  echo "=============================================================================="
  local one
  one=$(uptime | sed 's/.*load averages*: *//' | awk '{print $1}' | tr -d ',')
  # A number taken on a saturated box measures the box, not the change. The threshold is the
  # brief's: check load < 8 before measuring, DISCARD anything taken above 15.
  awk -v l="$one" 'BEGIN { if (l+0 > 15) { print "REFUSED: 1-minute load " l " > 15 — this measurement would be noise. Re-run on a quiet box."; exit 1 } if (l+0 >= 8) { print "WARNING: 1-minute load " l " >= 8 — the numbers below are usable only as an upper bound." } }' || exit 1
}

# Runs a measurement class and prints the stdout it captured. The JUnit XML is the artifact,
# not the build's exit code: a filtered Gradle run always trips `verifyTestsExecuted` by design,
# so reading the exit code here would report every successful measurement as a failure.
run_measurement() {
  local repo="$1" pattern="$2" module="$3"
  ( cd "$repo" && DP_MEASURE=1 ./gradlew ":modules:$module:test" --tests "$pattern" -q --rerun-tasks >/dev/null 2>&1 )
  python3 - "$repo/modules/$module/build/test-results/test" "$pattern" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
results, pattern = sys.argv[1], sys.argv[2].strip("*")
found = False
for path in glob.glob(f"{results}/*.xml"):
    root = ET.parse(path).getroot()
    if pattern not in (root.get("name") or ""):
        continue
    found = True
    for out in root.iter("system-out"):
        print((out.text or "").strip())
    for tc in root.iter("testcase"):
        for child in tc:
            if child.tag in ("failure", "error"):
                print(f"MEASUREMENT FAILED: {tc.get('name')}\n{(child.text or '')[:2000]}")
            if child.tag == "skipped":
                print(f"SKIPPED (DP_MEASURE not set?): {tc.get('name')}")
if not found:
    print(f"NO RESULTS for {pattern} — the run did not reach the class.")
PY
}
