#!/usr/bin/env bash
# §2 (production shape) — three independent source nodes on a REAL lane stack: two Postgres
# scans of 500k rows and a 200k-row lake read, all staging into tempdb, then one tempdb join.
#
# This is the measurement 108 §B specifies, against the deployed app with demo data rather than
# at the staging seam. What it answers is STRUCTURAL and therefore robust to a noisy box: do the
# three source nodes' [started_at, completed_at] windows INTERSECT? Under the pre-108 whole-drain
# lock they cannot; they are disjoint by construction.
#
# Needs: a running lane stack and an admin API key.
#   export APP_COMPOSE_PROJECT=dp108 APP_HOST_PORT=8108 IMAGE_TAG=dp108
#   ./app.sh --start --demo nyc,lake
#   export DP_MEASURE_KEY=<an admin key>   (or put KEY=<key> in /tmp/dp108-key.txt)
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=lib.sh
source "$REPO/scripts/measure/lib.sh"
measure_header "§2 (production shape) — three source nodes on the lane stack" || exit 1

PORT="${APP_HOST_PORT:-8108}"
BASE="http://localhost:$PORT"
KEY="${DP_MEASURE_KEY:-$(sed -n 's/^KEY=//p' /tmp/dp108-key.txt 2>/dev/null)}"
[ -n "$KEY" ] || { echo "No API key. Set DP_MEASURE_KEY, or put KEY=<key> in /tmp/dp108-key.txt."; exit 1; }
curl -sf "$BASE/ready" >/dev/null || { echo "No stack on $BASE — start it first (see the header of this file)."; exit 1; }

api() { curl -s -H "DP-API-Key: $KEY" -H "Content-Type: application/json" "$@"; }

template() { # id dialect body
  api -X POST "$BASE/api/v1/templates" -d "$(python3 -c '
import json,sys
print(json.dumps({"id":sys.argv[1],"dialect":sys.argv[2],"display_name":sys.argv[1],
                  "description":"108 §2 three-source measurement","imports":[],"body":sys.argv[3]}))
' "$1" "$2" "$3")" >/dev/null
}

echo "==> templates"
template "measure/108_pg_a.sql"  "POSTGRES" "SELECT g AS n, 1 AS lane FROM generate_series(1, 500000) g"
template "measure/108_pg_b.sql"  "POSTGRES" "SELECT g AS n, 2 AS lane FROM generate_series(1, 500000) g"
template "measure/108_lake.sql"  "LAKE"     "SELECT hvfhs_license_num AS n, 3 AS lane FROM hvfhv_trips_sample LIMIT 200000"
template "measure/108_join.sql"  "H2"       "SELECT lane, COUNT(*) AS c FROM stg_pg_a GROUP BY lane UNION ALL SELECT lane, COUNT(*) FROM stg_pg_b GROUP BY lane UNION ALL SELECT lane, COUNT(*) FROM stg_lake GROUP BY lane"

echo "==> pipeline"
PIPELINE_JSON=$(python3 - <<'PY'
import json
def src(nid, ds, tpl, table):
    return {"id": nid, "description": nid, "type": "DQL", "source": ds,
            "template": {"id": tpl, "version": 1},
            "output": {"target": "tempdb", "table": table}, "depends_on": []}
nodes = [
    src("src_pg_a", "sample-trips", "measure/108_pg_a.sql", "stg_pg_a"),
    src("src_pg_b", "sample-trips", "measure/108_pg_b.sql", "stg_pg_b"),
    src("src_lake", "sample-lake",  "measure/108_lake.sql", "stg_lake"),
    {"id": "joined", "description": "join the three", "type": "DQL", "source": "tempdb",
     "template": {"id": "measure/108_join.sql", "version": 1},
     "output": {"target": "caller"},
     # Data flow only: the join reads all three staged tables. No edge here serialises anything.
     "depends_on": ["src_pg_a", "src_pg_b", "src_lake"]},
]
print(json.dumps({"schema_version": 1, "name": "measure/108_three_source",
                  "display_name": "108 three-source", "description": "108 §2",
                  "parameters": {}, "nodes": nodes}))
PY
)
PID=$(api -X POST "$BASE/api/v1/pipelines" -d "$PIPELINE_JSON" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("data",{}).get("id",""))')
if [ -z "$PID" ]; then
  PID=$(api "$BASE/api/v1/pipelines?q=measure/108_three_source" | python3 -c 'import sys,json
d=json.load(sys.stdin).get("data",{})
items=d.get("items",d if isinstance(d,list) else [])
print(items[0]["id"] if items else "")')
fi
[ -n "$PID" ] || { echo "could not create or find the pipeline"; exit 1; }
echo "    pipeline $PID"

echo "==> execute"
# The execute endpoint STREAMS (text/event-stream); the id arrives on `execution_started`. The
# stream is consumed to EOF so the run completes, then the id is read back out of it — simpler
# and less racy than polling a listing for a row that may not exist yet.
EXEC=$(api -X POST "$BASE/api/v1/pipelines/$PID/execute" -H "Accept: text/event-stream" -d '{"parameters":{}}')
EID=$(echo "$EXEC" | sed -n 's/^data:.*"execution_id":"\([0-9a-f-]*\)".*/\1/p' | head -1)
[ -n "$EID" ] || { echo "no execution id in the stream: $(echo "$EXEC" | head -c 300)"; exit 1; }
echo "    execution $EID"

# The stream above already ran to completion, so the row is terminal by now — but a poll costs
# nothing and covers the case where the terminal write lands a beat after the stream closes.
# 600 iterations, not 120: at 500k rows a source node is MINUTES on a loaded box, and the first
# version of this loop gave up at ~2 minutes and then fed an empty ROW to the parser.
STATUS=""
for _ in $(seq 1 600); do
  ROW=$(api "$BASE/api/v1/executions/$EID")
  STATUS=$(echo "$ROW" | python3 -c 'import sys,json
try: print(json.load(sys.stdin).get("data",{}).get("status",""))
except Exception: print("")' 2>/dev/null)
  [ "$STATUS" = "RUNNING" ] || [ -z "$STATUS" ] || break
  sleep 2
done
# Re-fetch, so the row parsed below is never the loop's last transient read.
ROW=$(api "$BASE/api/v1/executions/$EID")
echo "$ROW" | python3 -c 'import sys,json; json.load(sys.stdin)' 2>/dev/null || {
  echo "the execution row did not come back as JSON: $(echo "$ROW" | head -c 200)"; exit 1; }

echo
echo "$ROW" | python3 - "$EID" <<'PY'
import sys, json, datetime
row = json.load(sys.stdin)["data"]
stats = [s for s in (row.get("node_stats") or []) if s.get("started_at")]
def ms(t): return int(datetime.datetime.fromisoformat(t.replace("Z", "+00:00")).timestamp() * 1000)
sources = [s for s in stats if s["node_id"].startswith("src_")]
if not sources:
    print("no source node stats on the row — did the execution reach them?"); raise SystemExit(1)
t0 = min(ms(s["started_at"]) for s in sources)
print(f"### Three source nodes on the lane stack — execution {sys.argv[1]}, status {row['status']}")
print()
print("| node | rows | started (ms from t0) | completed (ms from t0) | window |")
print("|---|---|---|---|---|")
for s in sorted(stats, key=lambda x: ms(x["started_at"])):
    a, b = ms(s["started_at"]), ms(s["completed_at"]) if s.get("completed_at") else None
    print(f"| {s['node_id']} | {s.get('rows_out')} | {a-t0} | {(b-t0) if b else '—'} | {(b-a) if b else '—'}ms |")
print()
print(f"execution wall time: {row.get('duration_ms')}ms")
wins = [(ms(s['started_at']), ms(s['completed_at'])) for s in sources if s.get('completed_at')]
print(f"sum of the three source windows: {sum(b-a for a,b in wins)}ms")
pairs = [(i, j) for i in range(len(wins)) for j in range(i+1, len(wins))]
overlap = all(wins[i][0] <= wins[j][1] and wins[j][0] <= wins[i][1] for i, j in pairs)
print()
print(f"pairwise window intersection: {'YES — the three staged concurrently' if overlap else 'NO — they were serialized'}")
print()
print("Under the pre-108 whole-drain lock the windows are disjoint BY CONSTRUCTION, so the line")
print("above is the structural claim; the wall time against the sum is the size of the win.")
PY
