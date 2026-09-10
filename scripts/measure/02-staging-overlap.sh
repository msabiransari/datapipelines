#!/usr/bin/env bash
# §2 — parallel staging windows and insert throughput — see scripts/measure/README.md.
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=lib.sh
source "$REPO/scripts/measure/lib.sh"
measure_header "§2 — parallel staging windows and insert throughput" || exit 1
run_measurement "$REPO" '*StagingOverlapMeasurement*' "dag"
