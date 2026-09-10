#!/usr/bin/env bash
# §1 — per-statement-kind driver timeout behaviour — see scripts/measure/README.md.
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=lib.sh
source "$REPO/scripts/measure/lib.sh"
measure_header "§1 — per-statement-kind driver timeout behaviour" || exit 1
run_measurement "$REPO" '*DriverTimeoutMeasurement*' "dag"
