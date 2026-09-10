#!/usr/bin/env bash
# §3 — staging RSS and engine contention — see scripts/measure/README.md.
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=lib.sh
source "$REPO/scripts/measure/lib.sh"
measure_header "§3 — staging RSS and engine contention" || exit 1
# The JVM's RSS, sampled from OUTSIDE while the measurement runs — the in-process reading
# cannot see it, and attributing the whole process's RSS to "one execution" would be a number
# with the wrong name on it. The sampler prints the peak it saw; the class prints the heap.
( while :; do
    ps -o rss=,command= -A 2>/dev/null | awk '/GradleWorkerMain|gradle.*Test/ && !/awk/ { printf "rss_kb=%s\n", $1 }'
    sleep 1
  done ) > /tmp/dp108-rss.$$ 2>/dev/null &
SAMPLER=$!
trap 'kill "$SAMPLER" 2>/dev/null' EXIT

run_measurement "$REPO" '*StagingPressureMeasurement*' "dag"

kill "$SAMPLER" 2>/dev/null
echo "### Test-JVM RSS sampled once a second while the above ran"
awk -F= '/rss_kb=/ { if ($2+0 > peak) peak = $2+0 } END { if (peak) printf "peak RSS: %.0f MB\n", peak/1024; else print "peak RSS: not sampled (no worker matched)" }' "/tmp/dp108-rss.$$"
rm -f "/tmp/dp108-rss.$$"
