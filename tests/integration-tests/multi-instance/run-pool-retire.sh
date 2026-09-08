#!/usr/bin/env bash
# 094 two-instance live verification harness — the RETIRE-THEN-CLOSE leg (§C).
#
# Proves, against TWO real app instances sharing one metadata Postgres and one
# Redis, the thing 094 changed:
#
#   A delete issued on app2 does NOT cut a query already running on app1.
#   Before 094, `ConnectionPoolManager.evict` removed the pool and `close()`d it
#   in the same breath, and HikariCP ABORTS the connections still in use once its
#   shutdown grace elapses — so the mid-statement execution failed. Now the pool
#   is RETIRED: out of the map at once (new leases miss it), soft-evicted, and
#   closed only when the statement finishes or at the configured ceiling.
#
# The measurement is a `pg_sleep` node on app1 whose statement is CONFIRMED live
# in pg_stat_activity before the delete lands, and whose execution is then
# required to reach status = SUCCESS with its rows. Every step prints a
# timestamp, because "the delete happened while the query was running" is a claim
# about ORDER and nothing else in the transcript would show it.
#
# The pipeline is deleted before the datasource on purpose: the §6.2 in-use guard
# is real, and that is the sequence an operator retiring a datasource follows.
#
# Full transcript: gate-logs/094-pool-retire.log (git-ignored).
# Always tears down with `docker compose -p mi094 down -v` (EXIT trap).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"
export MI036_REPO_ROOT="$ROOT"

PROJECT=mi094
IMAGE=datapipelines:local-mi094
export MI_IMAGE="$IMAGE"
# `-p` sets COMPOSE_PROJECT_NAME, which is what deploy/compose.yml interpolates into
# every volume name — so this lane's data is its own and the live `dp` stack is never
# touched. Exported as well as passed, because a bare `-p` leaves the interpolation
# reading the shell (the 2026-09-04 near-miss).
export COMPOSE_PROJECT_NAME="$PROJECT"
APP1_PORT=18080
APP2_PORT=18081
APP1=mi094-app1-1
APP2=mi094-app2-1
PG=mi094-postgres-1
ADMIN_EMAIL=mi-admin@example.com
SEED_PASSWORD=mi036-onetime   # the same bootstrap seed the 036/050 harnesses use
NEW_PASSWORD="mi094-changed-$(date +%s)"
LOG=gate-logs/094-pool-retire.log

# The sleep the node runs. Long enough that the two DELETEs land comfortably inside
# it, far under the executor's own node-query-timeout (60s) and under the retirement
# ceiling (90s) — a hard close here would be a FAILURE, not the expected path.
SLEEP_SECONDS=20

COMPOSE=(docker compose -p "$PROJECT"
  -f deploy/compose.yml
  -f deploy/compose.local-build.yml
  -f tests/integration-tests/multi-instance/compose.two-instance.yml
  --env-file deploy/env/defaults.env
  --env-file deploy/secrets.env)

mkdir -p gate-logs
exec > >(tee "$LOG") 2>&1

say()  { printf '\n===== %s | %s\n' "$(date '+%H:%M:%S')" "$*"; }
note() { printf '      %s | %s\n' "$(date '+%H:%M:%S')" "$*"; }
fail() { echo "HARNESS-FAIL: $*"; exit 1; }

teardown() {
  say "teardown: docker compose -p $PROJECT down -v"
  "${COMPOSE[@]}" down -v || true
}
trap teardown EXIT

psql_meta() { docker exec "$PG" psql -U datapipelines -d datapipelines -tAc "$1"; }

wait_ready() {
  local port=$1 timeout=$2
  local deadline=$((SECONDS + timeout))
  until curl -sf -o /dev/null "http://localhost:$port/ready"; do
    [ $SECONDS -lt $deadline ] || return 1
    sleep 3
  done
}

RESULT=FAIL

# ---------------------------------------------------------------- preflight
say "preflight"
[ -f modules/app/build/libs/datapipelines-app.jar ] || fail "bootJar missing — run ./gradlew :modules:app:bootJar"
if [ ! -f deploy/secrets.env ]; then
  SRC=/Users/msabir/development/projects/datapipelines/deploy/secrets.env
  [ -f "$SRC" ] || fail "deploy/secrets.env missing and no source at $SRC"
  cp "$SRC" deploy/secrets.env
  echo "copied deploy/secrets.env from $SRC (secrets stay local; file is git-ignored)"
fi
DB_PASSWORD=$(grep -E '^SPRING_DATASOURCE_PASSWORD=' deploy/secrets.env | head -1 | cut -d= -f2- | tr -d '"')
[ -n "$DB_PASSWORD" ] || fail "SPRING_DATASOURCE_PASSWORD not found in deploy/secrets.env"
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
# `set -o pipefail` makes a grep that matches NOTHING kill the script with no message, which is
# how the first run of this harness ended: 21 seconds of stack, one blank line, and no reason.
# The extraction is guarded and the page is dumped on failure — an unexplained exit is not a
# result anybody can act on.
LOGIN_PAGE=$(mktemp)
LOGIN_STATUS=$(curl -s -o "$LOGIN_PAGE" -w '%{http_code}' -c "$JAR1" "http://localhost:$APP1_PORT/login")
note "GET /login → $LOGIN_STATUS ($(wc -c < "$LOGIN_PAGE" | tr -d ' ') bytes)"
CSRF=$(grep -o 'name="_csrf" value="[^"]*"' "$LOGIN_PAGE" | head -1 | sed 's/.*value="//;s/"//' || true)
[ -n "$CSRF" ] || { echo "--- /login (first 60 lines) ---"; head -60 "$LOGIN_PAGE"; fail "no _csrf hidden field on /login (status $LOGIN_STATUS)"; }
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR1" -c "$JAR1" \
  --data-urlencode "email=$ADMIN_EMAIL" --data-urlencode "password=$SEED_PASSWORD" \
  --data-urlencode "_csrf=$CSRF" \
  "http://localhost:$APP1_PORT/login")
[ "$CODE" = "302" ] || fail "login POST answered $CODE, expected 302"

# The forced change is an HTMX form (`hx-post="/partials/account/password"`, partials/
# password-card.html) and carries NO hidden `_csrf` input — htmx sends the double-submit token
# as the `DP-CSRF-Token` HEADER, the same way the API-key mint below does. The 036/050 harnesses
# still post a hidden `_csrf` field here; that was true when they were written and is not any
# more, which is what the first run of this leg discovered. Reported in the handback rather than
# fixed there: those scripts are another round's ground.
curl -s -o "$LOGIN_PAGE" -b "$JAR1" -c "$JAR1" "http://localhost:$APP1_PORT/settings/password" >/dev/null
CSRF_COOKIE=$(grep 'dp_csrf' "$JAR1" | awk '{print $NF}')
[ -n "$CSRF_COOKIE" ] || fail "no dp_csrf cookie after login"
CODE=$(curl -s -o /tmp/mi094-pw.txt -w '%{http_code}' -b "$JAR1" -c "$JAR1" \
  -H "DP-CSRF-Token: $CSRF_COOKIE" \
  --data-urlencode "currentPassword=$SEED_PASSWORD" --data-urlencode "newPassword=$NEW_PASSWORD" \
  --data-urlencode "confirmPassword=$NEW_PASSWORD" \
  "http://localhost:$APP1_PORT/partials/account/password")
[ "$CODE" = "200" ] || { cat /tmp/mi094-pw.txt; fail "forced password change answered $CODE"; }

KEY_JSON=$(curl -s -b "$JAR1" -H "DP-CSRF-Token: $CSRF_COOKIE" -H 'Content-Type: application/json' \
  -d '{"name":"mi094-harness","scopes":["admin"]}' \
  "http://localhost:$APP1_PORT/api/v1/auth/api-keys")
API_KEY=$(echo "$KEY_JSON" | jq -r '.data.key // empty')
[ -n "$API_KEY" ] || fail "API key mint failed: $KEY_JSON"
echo "API key minted: ${API_KEY:0:12}…"
rm -f "$JAR1" "$LOGIN_PAGE"

# ---------------------------------------------------------------- fixtures
say "fixtures: pg-meta datasource, pg_sleep template + pipeline"
CODE=$(curl -s -o /tmp/mi094-ds.json -w '%{http_code}' -H "DP-API-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d "{\"name\":\"pg-meta\",\"display_name\":\"Metadata PG\",\"dialect\":\"POSTGRES\",\"jdbc_url\":\"jdbc:postgresql://postgres:5432/datapipelines\",\"username\":\"datapipelines\",\"password\":\"$DB_PASSWORD\"}" \
  "http://localhost:$APP1_PORT/api/v1/datasources")
[ "$CODE" = "201" ] || { cat /tmp/mi094-ds.json; fail "datasource create answered $CODE"; }

# The effective pool settings the 094 read surface publishes — quoted into the
# transcript because they are what the retirement is about.
say "the datasource's EFFECTIVE pool settings (§5, the new \`pool\` object)"
curl -s -H "DP-API-Key: $API_KEY" "http://localhost:$APP1_PORT/api/v1/datasources/pg-meta" | jq -c '.data.pool'

CODE=$(curl -s -o /tmp/mi094-tpl.json -w '%{http_code}' -H "DP-API-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d "{\"id\":\"test/pg_slow.sql\",\"dialect\":\"POSTGRES\",\"display_name\":\"Slow read\",\"description\":\"SELECT over pg_sleep — the 094 mid-query probe. Declares no parameters.\",\"imports\":[],\"body\":\"SELECT 'slept' AS v FROM (SELECT pg_sleep($SLEEP_SECONDS)) s\"}" \
  "http://localhost:$APP1_PORT/api/v1/templates")
[ "$CODE" = "201" ] || { cat /tmp/mi094-tpl.json; fail "template create answered $CODE"; }

PIPE_JSON=$(curl -s -H "DP-API-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d '{"schema_version":1,"name":"test/mi094_slow","display_name":"MI094 Slow","description":"094 retire-then-close harness: one DQL node that sleeps, caller output.","parameters":{},"nodes":[{"id":"slow","description":"pg_sleep","type":"DQL","source":"pg-meta","template":{"id":"test/pg_slow.sql","version":1},"depends_on":[]}]}' \
  "http://localhost:$APP1_PORT/api/v1/pipelines")
PIPELINE_ID=$(echo "$PIPE_JSON" | jq -r '.data.id // empty')
[ -n "$PIPELINE_ID" ] || fail "pipeline create failed: $PIPE_JSON"
echo "pipeline id: $PIPELINE_ID"

# ================================================================ THE MEASUREMENT
say "step 1: warm BOTH pools — a real mid-query delete finds pools that already exist"
for port in $APP1_PORT $APP2_PORT; do
  # A warm-up execution against the same datasource. It runs the same slow node, so this also
  # proves the pipeline works before anything is deleted.
  #
  # Written to a FILE and grepped afterwards, never piped into `grep -q`: grep exits on the
  # first match, curl takes SIGPIPE, and under `set -o pipefail` a perfectly good execution
  # reads as a failure. (It also closes the SSE client early, which trips the 30s
  # disconnect-grace cancellation — the 050 harness records the same reason.)
  WARM=$(mktemp)
  curl -sN -H "DP-API-Key: $API_KEY" -H 'Accept: text/event-stream' -H 'Content-Type: application/json' \
    -d '{"parameters":{}}' "http://localhost:$port/api/v1/pipelines/$PIPELINE_ID/execute" > "$WARM"
  grep -q 'data_ready' "$WARM" || { cat "$WARM"; rm -f "$WARM"; fail "warm-up execution on :$port did not reach data_ready"; }
  rm -f "$WARM"
  note "warm-up on :$port completed"
done

say "step 2: start the LONG execution on app1 (background SSE)"
SSE=$(mktemp)
curl -sN -H "DP-API-Key: $API_KEY" -H 'Accept: text/event-stream' -H 'Content-Type: application/json' \
  -d '{"parameters":{}}' "http://localhost:$APP1_PORT/api/v1/pipelines/$PIPELINE_ID/execute" > "$SSE" &
SSE_PID=$!
note "SSE curl pid=$SSE_PID"

say "step 3: confirm the statement is LIVE in the database before anything is deleted"
DEADLINE=$((SECONDS + 30))
LIVE=0
while [ $SECONDS -lt $DEADLINE ]; do
  LIVE=$(psql_meta "SELECT COUNT(*) FROM pg_stat_activity WHERE query LIKE '%pg_sleep%' AND query NOT LIKE '%pg_stat_activity%'")
  [ "$LIVE" -gt 0 ] && break
  sleep 1
done
[ "$LIVE" -gt 0 ] || fail "the pg_sleep statement never reached the database — the mid-query window never opened"
note "pg_stat_activity shows $LIVE live pg_sleep statement(s) — the window is OPEN"
psql_meta "SELECT pid, state, left(query, 60) FROM pg_stat_activity WHERE query LIKE '%pg_sleep%' AND query NOT LIKE '%pg_stat_activity%'" | sed 's/^/      pg | /'
EXEC_ID=$(psql_meta "SELECT execution_id FROM pipeline_executions ORDER BY started_at DESC LIMIT 1")
note "execution under test: $EXEC_ID (status=$(psql_meta "SELECT status FROM pipeline_executions WHERE execution_id = '$EXEC_ID'"))"

say "step 4: on app2 — delete the PIPELINE, then the DATASOURCE (the in-use guard's order)"
CODE=$(curl -s -o /tmp/mi094-delp.json -w '%{http_code}' -X DELETE -H "DP-API-Key: $API_KEY" \
  "http://localhost:$APP2_PORT/api/v1/pipelines/$PIPELINE_ID")
note "DELETE /pipelines/$PIPELINE_ID on app2 → $CODE"
[ "$CODE" = "204" ] || { cat /tmp/mi094-delp.json; fail "pipeline delete answered $CODE"; }

CODE=$(curl -s -o /tmp/mi094-deld.json -w '%{http_code}' -X DELETE -H "DP-API-Key: $API_KEY" \
  "http://localhost:$APP2_PORT/api/v1/datasources/pg-meta")
note "DELETE /datasources/pg-meta on app2 → $CODE"
[ "$CODE" = "204" ] || { cat /tmp/mi094-deld.json; fail "datasource delete answered $CODE"; }

note "the statement is STILL live: $(psql_meta "SELECT COUNT(*) FROM pg_stat_activity WHERE query LIKE '%pg_sleep%' AND query NOT LIKE '%pg_stat_activity%'")"

say "step 5: THE ASSERTION — the mid-query execution completes anyway"
wait $SSE_PID || true
grep -q 'data_ready' "$SSE" || { cat "$SSE"; fail "the mid-query execution did NOT complete — its connection was cut"; }
STATUS=$(psql_meta "SELECT status FROM pipeline_executions WHERE execution_id = '$EXEC_ID'")
note "execution $EXEC_ID final status: $STATUS"
[ "$STATUS" = "SUCCESS" ] || fail "expected SUCCESS, got $STATUS"
ROWS=$(curl -s -H "DP-API-Key: $API_KEY" "http://localhost:$APP1_PORT/api/v1/executions/$EXEC_ID/result" | jq -r '.data.rows[0][0]')
note "and it returned its rows: $ROWS"
[ "$ROWS" = "slept" ] || fail "expected the row value 'slept', got '$ROWS'"
rm -f "$SSE"

say "step 6: the retirement, in both instances' logs"
docker logs "$APP1" 2>&1 | grep -E 'pool_invalidated_remotely|pool_hard_closed|pool_reconcile' | tail -5 | sed 's/^/      app1 | /' || true
docker logs "$APP2" 2>&1 | grep -E 'pool_invalidated_remotely|pool_hard_closed|pool_reconcile' | tail -5 | sed 's/^/      app2 | /' || true
# The ceiling must NOT have fired: 20s of sleep is far under the 90s default, and a
# hard close here would mean the statement lost its connection after all.
HARD=$(docker logs "$APP1" 2>&1 | grep -c 'pool_hard_closed' || true)
note "app1 hard closes (must be 0): $HARD"
[ "$HARD" = "0" ] || fail "the ceiling fired on a statement that finished well inside it"
docker logs "$APP1" 2>&1 | grep -q 'pool_invalidated_remotely' \
  || fail "app1 never logged pool_invalidated_remotely — it did not learn about app2's delete"

say "step 7: the datasource is gone — neither instance will build a pool for it again"
for port in $APP1_PORT $APP2_PORT; do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "DP-API-Key: $API_KEY" "http://localhost:$port/api/v1/datasources/pg-meta")
  note "GET /datasources/pg-meta on :$port → $CODE (404 once the §6.3 60s metadata cache has turned over)"
done

RESULT=PASS
say "RESULT: RETIRE-THEN-CLOSE = $RESULT"
rm -f /tmp/mi094-ds.json /tmp/mi094-tpl.json /tmp/mi094-delp.json /tmp/mi094-deld.json /tmp/mi094-pw.txt
