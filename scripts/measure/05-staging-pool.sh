#!/usr/bin/env bash
# 146 / #118 — the bounded staging pool against whole pipelines at max-connections 1/2/4 —
# see scripts/measure/README.md. DP_MEASURE_CAPACITIES=1,4 narrows the arms.
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=lib.sh
source "$REPO/scripts/measure/lib.sh"
measure_header "146 — staging pool: fan-out + chain, slow drain + unrelated work, concurrent executions" || exit 1
run_measurement "$REPO" '*StagingPoolMeasurement*' "dag"
