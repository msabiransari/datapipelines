#!/usr/bin/env bash
# #266 — execution-event and audit persistence under N concurrent executions plus an open-loop
# audit load, through the REAL WebEventEmitter / AuditLogger against Postgres and Redis
# (Testcontainers) — see scripts/measure/README.md. DP_MEASURE_N=1,32 narrows the arms;
# DP_MEASURE_M (events per execution) and DP_MEASURE_AUDIT_RATE (audit rows/s) widen them.
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=lib.sh
source "$REPO/scripts/measure/lib.sh"
measure_header "266 — persistence: N executions × M events through the emitter, beside an audit load" || exit 1
run_measurement "$REPO" '*PersistenceMeasurement*' "web"
