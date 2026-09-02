#!/usr/bin/env bash
# 050 two-instance live verification harness — the pool-invalidation leg (R1/M3).
#
# Proves, against TWO real app instances sharing one metadata Postgres and one
# Redis, the mechanism the 050 round shipped:
#   POOL INVALIDATION: a datasource PUT on app1 → app2's subscriber evicts its
#   warm pool → app2's NEXT execution rebuilds from the NEW row (a second PG
#   login whose CURRENT_USER is distinguishable in the result rows), within
#   seconds — not at app2's next restart. The app2 log line
#   `event=datasource.pool_invalidated_remotely` is quoted from docker logs.
#
# Full transcript: gate-logs/050-pool-invalidation.log (git-ignored).
# Always tears down with `docker compose -p mi050 down -v` (EXIT trap).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"
export MI036_REPO_ROOT="$ROOT"

PROJECT=mi050
IMAGE=datapipelines:local-mi050
export MI_IMAGE="$IMAGE"
APP1_PORT=18080
APP2_PORT=18081
APP1=mi050-app1-1
APP2=mi050-app2-1
PG=mi050-postgres-1
ADMIN_EMAIL=mi-admin@example.com
SEED_PASSWORD=mi036-onetime   # same bootstrap seed as the 036 harness's application.yml path
NEW_PASSWORD="mi050-changed-$(date +%s)"
LOG=gate-logs/050-pool-invalidation.log

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

wait_ready() {
  local port=$1 timeout=$2
  local deadline=$((SECONDS + timeout))
  until curl -sf -o /dev/null "http://localhost:$port/ready"; do
    [ $SECONDS -lt $deadline ] || return 1
    sleep 3
  done
}

# execute_on <port> → execution id (blocking: the SSE stream ends with data_ready)
execute_on() {
  local port=$1 sse
  sse=$(mktemp)
  curl -sN -H "DP-API-Key: $API_KEY" -H 'Accept: text/event-stream' -H 'Content-Type: application/json' \
    -d '{"parameters":{}}' "http://localhost:$port/api/v1/pipelines/$PIPELINE_ID/execute" > "$sse"
  grep -q 'data_ready' "$sse" || { cat "$sse"; rm -f "$sse"; fail "no data_ready in SSE stream from :$port"; }
  rm -f "$sse"
  psql_meta "SELECT execution_id FROM pipeline_executions ORDER BY started_at DESC LIMIT 1"
}

# result_user <execution-id> → the CURRENT_USER the execution's pool served
result_user() {
  curl -s -H "DP-API-Key: $API_KEY" "http://localhost:$APP2_PORT/api/v1/executions/$1/result" | jq -r '.data.rows[0][0]'
}

POOL_RESULT=FAIL

# ---------------------------------------------------------------- preflight
say "preflight"
[ -f modules/app/build/libs/datapipelines-app.jar ] || fail "bootJar missing — run ./gradlew :modules:app:bootJar"
if [ ! -f deploy/.env ]; then
  SRC=/Users/msabir/development/projects/datapipelines/deploy/.env
  [ -f "$SRC" ] || fail "deploy/.env missing and no source at $SRC"
  cp "$SRC" deploy/.env
  echo "copied deploy/.env from $SRC (secrets stay local; file is git-ignored)"
fi
DB_PASSWORD=$(grep -E '^METADATA_DB_PASSWORD=' deploy/.env | head -1 | cut -d= -f2- | tr -d '"')
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
CSRF=$(printf '%s' "$LOGIN_HTML" | grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"//')
[ -n "$CSRF" ] || fail "no _csrf hidden field on /login"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR1" -c "$JAR1" \
  --data-urlencode "email=$ADMIN_EMAIL" --data-urlencode "password=$SEED_PASSWORD" \
  --data-urlencode "_csrf=$CSRF" \
  "http://localhost:$APP1_PORT/login")
[ "$CODE" = "302" ] || fail "login POST answered $CODE, expected 302"
grep -q 'dp_session' "$JAR1" || fail "login minted no dp_session cookie"
echo "login OK (302, dp_session minted)"

PW_HTML=$(curl -s -b "$JAR1" -c "$JAR1" "http://localhost:$APP1_PORT/settings/password")
CSRF=$(printf '%s' "$PW_HTML" | grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"//')
CODE=$(curl -s -o /tmp/mi050-pw.txt -w '%{http_code}' -b "$JAR1" -c "$JAR1" \
  --data-urlencode "currentPassword=$SEED_PASSWORD" --data-urlencode "newPassword=$NEW_PASSWORD" \
  --data-urlencode "confirmPassword=$NEW_PASSWORD" --data-urlencode "_csrf=$CSRF" \
  "http://localhost:$APP1_PORT/partials/account/password")
[ "$CODE" = "200" ] || { cat /tmp/mi050-pw.txt; fail "forced password change answered $CODE"; }
echo "forced password change OK (200)"

CSRF_COOKIE=$(grep 'dp_csrf' "$JAR1" | awk '{print $NF}')
KEY_JSON=$(curl -s -b "$JAR1" -H "DP-CSRF-Token: $CSRF_COOKIE" -H 'Content-Type: application/json' \
  -d '{"name":"mi050-harness","scopes":["admin"]}' \
  "http://localhost:$APP1_PORT/api/v1/auth/api-keys")
API_KEY=$(echo "$KEY_JSON" | jq -r '.data.key // empty')
[ -n "$API_KEY" ] || fail "API key mint failed: $KEY_JSON"
echo "API key minted: ${API_KEY:0:12}…"
rm -f "$JAR1"

# ---------------------------------------------------------------- fixtures
say "fixtures: second PG login, pg-meta datasource, current_user template + pipeline"
# The distinguishable identity: after the PUT, app2's rebuilt pool connects as mi050_b
# (same password as the metadata user — the PUT keeps the stored credential).
docker exec "$PG" psql -U datapipelines -d datapipelines -tAc \
  "CREATE ROLE mi050_b LOGIN PASSWORD '$DB_PASSWORD'" >/dev/null 2>&1 \
  || docker exec "$PG" psql -U datapipelines -d datapipelines -tAc \
    "ALTER ROLE mi050_b LOGIN PASSWORD '$DB_PASSWORD'" >/dev/null
docker exec "$PG" psql -U datapipelines -d datapipelines -tAc \
  "GRANT CONNECT ON DATABASE datapipelines TO mi050_b" >/dev/null
echo "second PG login mi050_b ready (same password as datapipelines)"

CODE=$(curl -s -o /tmp/mi050-ds.json -w '%{http_code}' -H "DP-API-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d "{\"name\":\"pg-meta\",\"display_name\":\"Metadata PG\",\"dialect\":\"POSTGRES\",\"jdbc_url\":\"jdbc:postgresql://postgres:5432/datapipelines\",\"username\":\"datapipelines\",\"password\":\"$DB_PASSWORD\"}" \
  "http://localhost:$APP1_PORT/api/v1/datasources")
[ "$CODE" = "201" ] || { cat /tmp/mi050-ds.json; fail "datasource create answered $CODE"; }

CODE=$(curl -s -o /tmp/mi050-tpl.json -w '%{http_code}' -H "DP-API-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d '{"id":"pg_who.sql","dialect":"POSTGRES","display_name":"Current user","description":"SELECT current_user — the 050 harness identity probe. Declares no parameters.","imports":[],"body":"SELECT current_user AS usr"}' \
  "http://localhost:$APP1_PORT/api/v1/templates")
[ "$CODE" = "201" ] || { cat /tmp/mi050-tpl.json; fail "template create answered $CODE"; }

PIPE_JSON=$(curl -s -H "DP-API-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d '{"schema_version":1,"name":"mi050_whoami","display_name":"MI050 Whoami","description":"050 pool-invalidation harness: one DQL node reading current_user, caller output.","parameters":{},"nodes":[{"id":"who","description":"current_user","type":"DQL","source":"pg-meta","template":{"id":"pg_who.sql","version":1},"depends_on":[]}]}' \
  "http://localhost:$APP1_PORT/api/v1/pipelines")
PIPELINE_ID=$(echo "$PIPE_JSON" | jq -r '.data.id // empty')
[ -n "$PIPELINE_ID" ] || fail "pipeline create failed: $PIPE_JSON"
echo "pipeline id: $PIPELINE_ID"

# ================================================================ POOL INVALIDATION TEST
say "step 1: execute on app2 (:$APP2_PORT) — warms app2's pool from the OLD row"
EXEC1=$(execute_on $APP2_PORT)
USER1=$(result_user "$EXEC1")
echo "execution $EXEC1 served by app2 → current_user=$USER1"
[ "$USER1" = "datapipelines" ] || fail "expected the OLD row's user (datapipelines), got $USER1"
docker logs "$APP2" 2>&1 | grep -c 'pool_invalidated_remotely' | sed 's/^/  app2 remote-invalidation lines so far: /' || true

say "step 2: PUT the datasource on app1 (:$APP1_PORT) — username → mi050_b (password kept)"
CODE=$(curl -s -o /tmp/mi050-put.json -w '%{http_code}' -X PUT -H "DP-API-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d "{\"display_name\":\"Metadata PG\",\"dialect\":\"POSTGRES\",\"jdbc_url\":\"jdbc:postgresql://postgres:5432/datapipelines\",\"username\":\"mi050_b\"}" \
  "http://localhost:$APP1_PORT/api/v1/datasources/pg-meta")
[ "$CODE" = "200" ] || { cat /tmp/mi050-put.json; fail "datasource PUT answered $CODE"; }
echo "PUT on app1 answered 200 — row committed, local pool evicted synchronously, name published"

say "step 3: app2's subscriber must evict — quote its log line"
sleep 2
docker logs "$APP2" 2>&1 | grep 'pool_invalidated_remotely' | tail -2 | sed 's/^/  app2 | /'
docker logs "$APP2" 2>&1 | grep -q 'pool_invalidated_remotely' || fail "app2 never logged pool_invalidated_remotely"

say "step 4: app2's NEXT execution rebuilds from the NEW row (budget 30s — not app2's restart)"
USER2=""
DEADLINE=$((SECONDS + 30))
while [ $SECONDS -lt $DEADLINE ]; do
  EXEC=$(execute_on $APP2_PORT)
  USER2=$(result_user "$EXEC")
  echo "  [$(date '+%H:%M:%S')] execution $EXEC → current_user=$USER2"
  [ "$USER2" = "mi050_b" ] && break
  sleep 2
done
[ "$USER2" = "mi050_b" ] || fail "app2 kept serving the OLD pool's user ($USER2) — M3 alive?"
echo "app2's next execution served current_user=$USER2 — pool rebuilt from the new row"

say "control: app1's own executions never double-evict (its eviction was synchronous)"
docker logs "$APP1" 2>&1 | { grep -c 'pool_invalidated_remotely' || true; } | sed 's/^/  app1 remote-invalidation lines (must be 0): /'
[ "$(docker logs "$APP1" 2>&1 | { grep -c 'pool_invalidated_remotely' || true; })" = "0" ] || fail "app1 reacted to its own message"

POOL_RESULT=PASS
say "RESULT: POOL INVALIDATION = $POOL_RESULT"
rm -f /tmp/mi050-ds.json /tmp/mi050-tpl.json /tmp/mi050-put.json
