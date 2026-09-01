#!/usr/bin/env bash
# 036 two-instance live verification harness — see README.md in this directory.
#
# Proves, against TWO real app instances sharing one metadata Postgres:
#   DRAIN: SIGTERM on the instance running an execution → the row reaches
#          ABORTED via the shutdown drain (no instance_lost), readiness flips
#          before cancellation, and Statement.cancel reaches the source DB.
#   SWEEP: SIGKILL on the instance running an execution → the row stays
#          RUNNING until the SURVIVOR's StaleExecutionSweeper flips it to
#          ABORTED with pipeline.execution.instance_lost.
#
# Full transcript: gate-logs/036-two-instance.log (git-ignored).
# Always tears down with `docker compose -p mi036 down -v` (EXIT trap).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"
export MI036_REPO_ROOT="$ROOT"

PROJECT=mi036
IMAGE=datapipelines:local-mi036
APP1_PORT=18080
APP2_PORT=18081
APP1=mi036-app1-1
APP2=mi036-app2-1
PG=mi036-postgres-1
ADMIN_EMAIL=mi-admin@example.com
SEED_PASSWORD=mi036-onetime
NEW_PASSWORD="mi036-changed-$(date +%s)"
LOG=gate-logs/036-two-instance.log

COMPOSE=(docker compose -p "$PROJECT"
  -f deploy/docker-compose.yml
  -f deploy/docker-compose.local.yml
  -f tests/integration-tests/multi-instance/docker-compose.two-instance.yml)

mkdir -p gate-logs
exec > >(tee "$LOG") 2>&1

say()  { printf '\n===== %s | %s\n' "$(date '+%H:%M:%S')" "$*"; }
fail() { echo "HARNESS-FAIL: $*"; exit 1; }

teardown() {
  say "teardown: docker compose -p $PROJECT down -v"
  "${COMPOSE[@]}" down -v || true
}
trap teardown EXIT

psql_meta() { docker exec "$PG" psql -U datapipelines -d datapipelines -tAc "$1"; }

latest_execution() {
  psql_meta "SELECT execution_id || '|' || status || '|' || coalesce(error_json::text,'') FROM pipeline_executions ORDER BY started_at DESC LIMIT 1"
}

# wait_ready <port> <timeout-s>
wait_ready() {
  local port=$1 timeout=$2
  local deadline=$((SECONDS + timeout))
  until curl -sf -o /dev/null "http://localhost:$port/ready"; do
    [ $SECONDS -lt $deadline ] || return 1
    sleep 3
  done
}

# poll_execution_status <exec-id> <wanted-regex> <timeout-s> → prints "status|error_json"
poll_execution_status() {
  local id=$1 wanted=$2 timeout=$3
  local deadline=$((SECONDS + timeout)) row status
  while [ $SECONDS -lt $deadline ]; do
    row=$(psql_meta "SELECT status || '|' || coalesce(error_json::text,'') FROM pipeline_executions WHERE execution_id = '$id'")
    status=${row%%|*}
    echo "  [$(date '+%H:%M:%S')] poll $id → status=$status" >&2
    if [[ $status =~ $wanted ]]; then echo "$row"; return 0; fi
    # poll progress goes to stderr — stdout is the captured result row
    sleep 5
  done
  return 1
}

DRAIN_RESULT=FAIL
SWEEP_RESULT=FAIL

# ---------------------------------------------------------------- preflight
say "preflight"
[ -f modules/app/build/libs/datapipelines-app.jar ] || fail "bootJar missing — run ./gradlew :modules:app:bootJar"
if [ ! -f deploy/.env ]; then
  SRC=/Users/msabir/development/projects/datapipelines/deploy/.env
  [ -f "$SRC" ] || fail "deploy/.env missing and no source at $SRC"
  cp "$SRC" deploy/.env
  echo "copied deploy/.env from $SRC (secrets stay local; file is git-ignored)"
fi
DB_PASSWORD=$(grep -E '^METADATA_DB_PASSWORD=' deploy/.env | head -1 | cut -d= -f2- | tr -d '"'"'")
[ -n "$DB_PASSWORD" ] || fail "METADATA_DB_PASSWORD not found in deploy/.env"
"${COMPOSE[@]}" config --quiet || fail "compose config invalid"
docker image inspect "$IMAGE" >/dev/null 2>&1 || docker build -t "$IMAGE" .
echo "image: $(docker image inspect "$IMAGE" --format '{{.Id}} {{.Created}}')"

# ---------------------------------------------------------------- stack up
say "stack up: postgres redis app1 app2"
"${COMPOSE[@]}" up -d postgres redis app1 app2
docker compose -p "$PROJECT" ps --format '{{.Name}} {{.Status}}'
wait_ready $APP1_PORT 300 || { docker logs "$APP1" | tail -40; fail "app1 never became ready"; }
wait_ready $APP2_PORT 300 || { docker logs "$APP2" | tail -40; fail "app2 never became ready"; }
echo "both apps ready: app1=:$APP1_PORT app2=:$APP2_PORT"

# ---------------------------------------------------------------- auth setup
say "auth setup (against app1): login → forced password change → mint API key"
JAR1=$(mktemp)
LOGIN_HTML=$(curl -s -c "$JAR1" "http://localhost:$APP1_PORT/login")
CSRF=$(echo "$LOGIN_HTML" | grep -oE 'name="_csrf" value="[^"]+"' | head -1 | sed -E 's/.*value="([^"]+)".*/\1/')
[ -n "$CSRF" ] || fail "no _csrf hidden field on /login"

CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR1" -c "$JAR1" \
  --data-urlencode "_csrf=$CSRF" \
  --data-urlencode "email=$ADMIN_EMAIL" \
  --data-urlencode "password=$SEED_PASSWORD" \
  "http://localhost:$APP1_PORT/login")
[ "$CODE" = "302" ] || fail "login POST answered $CODE, expected 302"
grep -q 'dp_session' "$JAR1" || fail "login minted no dp_session cookie"
echo "login OK (302, dp_session minted)"

PW_HTML=$(curl -s -b "$JAR1" -c "$JAR1" "http://localhost:$APP1_PORT/settings/password")
CSRF2=$(echo "$PW_HTML" | grep -oE 'name="_csrf" value="[^"]+"' | head -1 | sed -E 's/.*value="([^"]+)".*/\1/')
[ -n "$CSRF2" ] || CSRF2=$CSRF
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR1" -c "$JAR1" \
  --data-urlencode "_csrf=$CSRF2" \
  --data-urlencode "currentPassword=$SEED_PASSWORD" \
  --data-urlencode "newPassword=$NEW_PASSWORD" \
  --data-urlencode "confirmPassword=$NEW_PASSWORD" \
  "http://localhost:$APP1_PORT/partials/account/password")
[ "$CODE" = "200" ] || fail "change-password answered $CODE, expected 200"
echo "forced password change OK (200)"

CSRF_COOKIE=$(awk '$6=="dp_csrf"{v=$7} END{print v}' "$JAR1")
KEY_JSON=$(curl -s -b "$JAR1" -H "DP-CSRF-Token: $CSRF_COOKIE" -H 'Content-Type: application/json' \
  -d '{"name":"mi036-harness","scopes":["admin"]}' \
  "http://localhost:$APP1_PORT/api/v1/auth/api-keys")
API_KEY=$(echo "$KEY_JSON" | jq -r '.data.key // empty')
[ -n "$API_KEY" ] || fail "API key mint failed: $KEY_JSON"
echo "API key minted: ${API_KEY:0:12}…"
rm -f "$JAR1"

# ---------------------------------------------------------------- fixtures
say "fixtures: datasource (metadata PG), pg_sleep template, one-node pipeline"
CODE=$(curl -s -o /tmp/mi036-ds.json -w '%{http_code}' -H "DP-API-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d "{\"name\":\"pg-meta\",\"display_name\":\"Metadata PG\",\"dialect\":\"POSTGRES\",\"jdbc_url\":\"jdbc:postgresql://postgres:5432/datapipelines\",\"username\":\"datapipelines\",\"password\":\"$DB_PASSWORD\"}" \
  "http://localhost:$APP1_PORT/api/v1/datasources")
[ "$CODE" = "201" ] || { cat /tmp/mi036-ds.json; fail "datasource create answered $CODE"; }

CODE=$(curl -s -o /tmp/mi036-tpl.json -w '%{http_code}' -H "DP-API-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d '{"id":"pg_sleep.sql","dialect":"POSTGRES","display_name":"Sleep 300","description":"SELECT pg_sleep(300) — the 036 harness slow query. Declares no parameters.","imports":[],"body":"SELECT pg_sleep(300) AS slept"}' \
  "http://localhost:$APP1_PORT/api/v1/templates")
[ "$CODE" = "201" ] || { cat /tmp/mi036-tpl.json; fail "template create answered $CODE"; }

PIPE_JSON=$(curl -s -H "DP-API-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d '{"schema_version":1,"name":"mi036_slow_sleep","display_name":"MI036 Slow Sleep","description":"036 two-instance harness: one DQL node running pg_sleep(300), caller output.","parameters":{},"nodes":[{"id":"sleep","description":"pg_sleep(300)","type":"DQL","source":"pg-meta","template":{"id":"pg_sleep.sql","version":1},"depends_on":[]}]}' \
  "http://localhost:$APP1_PORT/api/v1/pipelines")
PIPELINE_ID=$(echo "$PIPE_JSON" | jq -r '.data.id // empty')
[ -n "$PIPELINE_ID" ] || fail "pipeline create failed: $PIPE_JSON"
echo "pipeline id: $PIPELINE_ID"

# start_execution <port> <sse-out-file> → echoes background curl PID
start_execution() {
  curl -sN -H "DP-API-Key: $API_KEY" -H 'Accept: text/event-stream' -H 'Content-Type: application/json' \
    -d '{"parameters":{}}' "http://localhost:$1/api/v1/pipelines/$PIPELINE_ID/execute" > "$2" 2>&1 &
  echo $!
}

wait_running() { # → prints execution_id
  local deadline=$((SECONDS + 60)) row id status
  while [ $SECONDS -lt $deadline ]; do
    row=$(latest_execution); id=${row%%|*}; rest=${row#*|}; status=${rest%%|*}
    if [ "$status" = "RUNNING" ]; then echo "$id"; return 0; fi
    sleep 2
  done
  echo "last row seen: $row" >&2
  return 1
}

pg_sleep_activity() {
  # pid <> pg_backend_pid(): the probe's own query text contains "pg_sleep"
  # and would self-match (observed — first run failed B3 on the probe itself).
  psql_meta "SELECT pid || ' | ' || state || ' | ' || left(query, 60) FROM pg_stat_activity WHERE query LIKE '%pg_sleep%' AND state = 'active' AND pid <> pg_backend_pid()"
}

# ================================================================ DRAIN TEST
say "DRAIN TEST: execute on app1 (:$APP1_PORT), then SIGTERM $APP1"
SSE1=$(mktemp)
CURL1_PID=$(start_execution $APP1_PORT "$SSE1")
echo "sse curl pid=$CURL1_PID (kept open until the container dies — early close triggers the 30s disconnect-grace cancellation)"
EXEC1=$(wait_running) || fail "drain-test execution never reached RUNNING"
echo "execution $EXEC1 is RUNNING on app1"

say "B3-before: pg_stat_activity for pg_sleep (Statement.cancel must make this disappear)"
pg_sleep_activity

docker kill -s SIGTERM "$APP1"
echo "SIGTERM sent to $APP1 at $(date '+%H:%M:%S')"

ROW1=$(poll_execution_status "$EXEC1" 'ABORTED|SUCCESS|FAILED' 90) || ROW1=$(psql_meta "SELECT status || '|' || coalesce(error_json::text,'') FROM pipeline_executions WHERE execution_id = '$EXEC1'")
STATUS1=${ROW1%%|*}; ERR1=${ROW1#*|}
echo "drain-test terminal row: status=$STATUS1 error_json=$ERR1"

wait "$CURL1_PID" 2>/dev/null || true
echo "--- SSE stream tail (app1 execution) ---"; tail -5 "$SSE1" || true

APP1_LOG=$(mktemp)
docker logs "$APP1" > "$APP1_LOG" 2>&1 || true
say "app1 shutdown events (order is the contract: readiness_refused BEFORE drain_cancelled)"
LINE_REFUSED=$(grep -n 'event=shutdown.readiness_refused' "$APP1_LOG" | head -1 || true)
LINE_CANCELLED=$(grep -n 'event=shutdown.drain_cancelled' "$APP1_LOG" | head -1 || true)
LINE_COMPLETE=$(grep -n 'event=shutdown.drain_complete' "$APP1_LOG" | head -1 || true)
echo "$LINE_REFUSED"
echo "$LINE_CANCELLED"
echo "$LINE_COMPLETE"
N_REFUSED=${LINE_REFUSED%%:*}; N_CANCELLED=${LINE_CANCELLED%%:*}

say "B3-after: pg_stat_activity for pg_sleep (expect empty — cancel reached the source DB)"
sleep 2
ACT_AFTER=$(pg_sleep_activity)
echo "${ACT_AFTER:-<empty>}"

if [ "$STATUS1" = "ABORTED" ] && [[ "$ERR1" != *instance_lost* ]] \
   && [ -n "$N_REFUSED" ] && [ -n "$N_CANCELLED" ] && [ "$N_REFUSED" -lt "$N_CANCELLED" ] \
   && [ -z "$ACT_AFTER" ]; then
  DRAIN_RESULT=PASS
fi
echo "DRAIN TEST: $DRAIN_RESULT"

# ---------------------------------------------------------------- restart app1
say "recreate app1 for the sweep test"
"${COMPOSE[@]}" up -d app1
wait_ready $APP1_PORT 300 || { docker logs "$APP1" | tail -40; fail "app1 did not come back ready"; }
echo "app1 ready again"

# ================================================================ SWEEP TEST
say "SWEEP TEST: execute on app2 (:$APP2_PORT), then SIGKILL $APP2"
SSE2=$(mktemp)
CURL2_PID=$(start_execution $APP2_PORT "$SSE2")
echo "sse curl pid=$CURL2_PID"
EXEC2=$(wait_running) || fail "sweep-test execution never reached RUNNING"
STARTED2=$(psql_meta "SELECT started_at FROM pipeline_executions WHERE execution_id = '$EXEC2'")
echo "execution $EXEC2 is RUNNING on app2 (started_at=$STARTED2)"

docker kill "$APP2"
echo "SIGKILL sent to $APP2 at $(date '+%H:%M:%S')"
sleep 5
STILL=$(psql_meta "SELECT status FROM pipeline_executions WHERE execution_id = '$EXEC2'")
echo "5s after SIGKILL: status=$STILL (expect RUNNING — no drain on SIGKILL)"

say "waiting for the survivor (app1) sweeper: stale-timeout=1m + 60s cadence (timeout 200s)"
ROW2=$(poll_execution_status "$EXEC2" 'ABORTED' 200) || ROW2=$(psql_meta "SELECT status || '|' || coalesce(error_json::text,'') FROM pipeline_executions WHERE execution_id = '$EXEC2'")
STATUS2=${ROW2%%|*}; ERR2=${ROW2#*|}
echo "sweep-test terminal row: status=$STATUS2 error_json=$ERR2"

wait "$CURL2_PID" 2>/dev/null || true

say "survivor app1 sweep evidence (event=execution.swept)"
SWEPT_LINE=$(docker logs "$APP1" 2>&1 | grep 'event=execution.swept' | tail -1 || true)
echo "${SWEPT_LINE:-<none>}"
echo "victim app2 logs show NO drain events (SIGKILL):"
N_SHUTDOWN=$(docker logs "$APP2" 2>&1 | grep -c 'event=shutdown\.' || true)
echo "$N_SHUTDOWN shutdown.* lines in victim logs"

if [ "$STATUS2" = "ABORTED" ] && [[ "$ERR2" == *instance_lost* ]] && [ "$STILL" = "RUNNING" ] && [ -n "$SWEPT_LINE" ]; then
  SWEEP_RESULT=PASS
fi
echo "SWEEP TEST: $SWEEP_RESULT"

# ---------------------------------------------------------------- verdict
say "VERDICT"
echo "drain test: $DRAIN_RESULT"
echo "sweep test: $SWEEP_RESULT"
rm -f "$SSE1" "$SSE2" "$APP1_LOG" /tmp/mi036-ds.json /tmp/mi036-tpl.json
[ "$DRAIN_RESULT" = PASS ] && [ "$SWEEP_RESULT" = PASS ]
