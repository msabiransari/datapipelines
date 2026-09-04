#!/usr/bin/env bash
# deploy/sample-data/selftest.sh — adversarial proof for load.sh's handling of
# operator-supplied values (045 §A) and the loader contract points that round
# touched. NOT a gate task, same philosophy as scripts/sample-data/verify.sh:
# it needs Docker, the two pinned images and a couple of minutes. Run it when
# touching deploy/sample-data/load.sh:
#
#   ./deploy/sample-data/selftest.sh
#
# It builds a THROWAWAY artifact set with the pinned images (the point is the
# loader's SQL quoting, not the data), serves it over a localhost HTTP server,
# runs load.sh inside the same pinned images the compose demo profile uses —
# postgres image for postgres+sqlite, mysql image for mysql, exactly like the
# two one-shot services — and asserts:
#
#   1. a demo password containing quotes, backslashes, dollar signs, double
#      quotes, semicolons, SQL comment markers and a space LOADS and then
#      AUTHENTICATES as the demo login on BOTH engines (045 §A — the
#      falsification: on the pre-045 loader this dies in the charset guard);
#   2. a password containing the Postgres dollar-quote tag is REFUSED up front,
#      naming the variable;
#   3. a role name outside [a-z_][a-z0-9_]{0,62} is refused the same way;
#   4. a corrupted artifact is refused with NOTHING written to any engine;
#   5. a re-load over a populated, marker-less database succeeds (the F2
#      contract), and a same-version re-run skips by marker;
#   6. the password never appears anywhere in the loader's own output.
#
# The demo login must authenticate for real, so the scratch Postgres runs with
# password auth (POSTGRES_HOST_AUTH_METHOD is NOT trust, unlike the build-side
# engines in scripts/sample-data/lib/engines.sh). Everything scratch — network,
# containers, HTTP server, temp dir — is PID-scoped and removed by trap.
#
# shellcheck disable=SC2015
# `test && ok x || bad y` is this script's assert idiom: bad runs exactly when
# the test fails; ok() is a printf that cannot itself fail.
# shellcheck disable=SC2034
# SD_SCRIPT is read by the sourced lib/common.sh.
# shellcheck disable=SC1091
# lib/common.sh is sourced at runtime by path.

set -euo pipefail
SD_SCRIPT=selftest
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SD_ROOT="$REPO_ROOT/scripts/sample-data"
source "$SD_ROOT/lib/common.sh"

require_cmd docker "selftest runs the loader inside the pinned images"
require_cmd sqlite3 "selftest builds a tiny nyc_reference.db"
require_cmd python3 "selftest builds the manifest and serves HTTP"

NET="$SD_PROJECT-$$-net"
PG="$SD_PROJECT-$$-pg"
MY="$SD_PROJECT-$$-mysql"
SRV_PID=""
cleanup() {
  [ -n "$SRV_PID" ] && kill "$SRV_PID" >/dev/null 2>&1 || true
  docker rm -f "$PG" "$MY" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT

PASS=0
FAIL=0
ok()  { printf 'selftest: ok   — %s\n' "$*"; PASS=$((PASS + 1)); }
bad() { printf 'selftest: FAIL — %s\n' "$*" >&2; FAIL=$((FAIL + 1)); }

# The adversarial passwords (045 §A): ' and \ are the round's stated
# requirements; ; -- " $ and a space round out the SQL-meaningful set.
PW_PG='it'"'"'s a \test;--$"pg'
PW_MY='it'"'"'s a \test;--$"my'

WORK="$(mktemp -d "${TMPDIR:-/tmp}/sd045.XXXXXX")"
ART="$WORK/v1"
SRVD="$WORK/sample"
mkdir -p "$ART" "$SRVD"

# --- scratch engines ---------------------------------------------------------
# User-defined network so the loader containers can reach the engines by name,
# like the compose demo services do. TCP probes throughout: the entrypoints'
# temporary servers listen on the socket only, and a socket probe reports ready
# while the real server is still down (the discipline documented in
# scripts/sample-data/lib/engines.sh).
step "scratch engines on the pinned images"
docker network create "$NET" >/dev/null

docker run -d --name "$PG" --network "$NET" \
  -e POSTGRES_DB=dp_sample_trips -e POSTGRES_USER=build -e POSTGRES_PASSWORD=build \
  "$(image_ref postgres)" >/dev/null
docker run -d --name "$MY" --network "$NET" \
  -e MYSQL_DATABASE=dp_sample_weather -e MYSQL_ROOT_PASSWORD=build \
  "$(image_ref mysql)" >/dev/null

pg_wait() {
  local i
  for i in $(seq 1 120); do
    docker exec "$PG" pg_isready -h 127.0.0.1 -U build -d dp_sample_trips >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}
my_wait() {
  local _
  for _ in $(seq 1 180); do
    docker exec -e MYSQL_PWD=build "$MY" \
      mysql --protocol=TCP -h 127.0.0.1 -uroot -D dp_sample_weather -e 'SELECT 1' >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}
pg_wait || die "scratch Postgres never became ready"
my_wait || die "scratch MySQL never became ready"
log "engines up (password auth on: the demo login must authenticate for real)"

# --- throwaway artifact set --------------------------------------------------
step "building the throwaway artifact set"
docker exec -e PGPASSWORD=build "$PG" \
  psql -h 127.0.0.1 -U build -d dp_sample_trips -qc \
  "CREATE TABLE trips(id INT); INSERT INTO trips VALUES (1),(2);"
docker exec -e PGPASSWORD=build "$PG" pg_dump -U build -Fc dp_sample_trips > "$ART/pg-trips.dump"

docker exec -e MYSQL_PWD=build "$MY" mysql --protocol=TCP -h 127.0.0.1 -uroot -e \
  "CREATE TABLE dp_sample_weather.stations(id INT PRIMARY KEY); INSERT INTO dp_sample_weather.stations VALUES (1);"
docker exec -e MYSQL_PWD=build "$MY" \
  mysqldump --protocol=TCP -h 127.0.0.1 -uroot --databases dp_sample_weather | gzip > "$ART/mysql-weather.sql.gz"

sqlite3 "$ART/nyc_reference.db" "CREATE TABLE zones(id INT); INSERT INTO zones VALUES (132);"
printf '{"templates": [], "pipelines": []}\n' > "$ART/examples.json"

# The manifest in the exact shape load.sh parses (fixed shape by design —
# neither pinned image ships jq or python).
python3 - "$ART" <<'PY'
import hashlib, json, os, sys
art = sys.argv[1]
def sha(p):
    return hashlib.sha256(open(p, "rb").read()).hexdigest()
files = ["pg-trips.dump", "mysql-weather.sql.gz", "nyc_reference.db", "examples.json"]
m = {"schema_version": 1, "dataset": "selftest", "version": "v1",
     "artifacts": [{"file": f, "sha256": sha(os.path.join(art, f)),
                    "bytes": os.path.getsize(os.path.join(art, f))} for f in files],
     "tables": [], "provenance": []}
open(os.path.join(art, "manifest.json"), "w").write(json.dumps(m, indent=2))
PY

# --- artifact HTTP server ----------------------------------------------------
SRV_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')
python3 -m http.server "$SRV_PORT" --bind 127.0.0.1 --directory "$WORK" >/dev/null 2>&1 &
SRV_PID=$!
disown "$SRV_PID" # keep bash's job-control "Terminated" notice out of the report
BASE_URL="http://host.docker.internal:$SRV_PORT"

run_loader() { # <image> <log> <engines>
  local image="$1" logfile="$2" engines="$3" rc=0
  shift 3
  docker run --rm --network "$NET" \
    -v "$SCRIPT_DIR":/opt/sample-data:ro \
    -v "$SRVD":/srv/sample \
    -e SAMPLE_DIR=/srv/sample \
    "$@" \
    "$image" sh /opt/sample-data/load.sh "$BASE_URL" v1 --engines "$engines" \
    >"$logfile" 2>&1 || rc=$?
  return "$rc"
}

# run_loader <image> <log> <engines> [extra docker args...]
# Runs load.sh the way the compose demo profile does: pinned image, script
# read-only at /opt/sample-data, SAMPLE_DIR on a scratch mount. Loader output
# is CAPTURED so the selftest can assert the password never appears in it.
pg_run() { run_loader "$(image_ref postgres)" "$1" postgres,sqlite \
  -e PGHOST="$PG" -e PGUSER=build -e PGPASSWORD=build -e PGDATABASE=postgres \
  -e SAMPLE_DB_USER=dp_demo_ro "${@:2}"; }
my_run() { run_loader "$(image_ref mysql)" "$1" mysql \
  -e MYSQL_HOST="$MY" -e MYSQL_ROOT_PASSWORD=build \
  -e SAMPLE_DB_USER=dp_demo_ro "${@:2}"; }

demo_psql() { docker exec -e PGPASSWORD="$1" "$PG" \
  psql -h 127.0.0.1 -U dp_demo_ro -d dp_sample_trips -tAc "$2"; }
demo_mysql() { docker exec -e MYSQL_PWD="$1" "$MY" \
  mysql --protocol=TCP -h 127.0.0.1 -u dp_demo_ro -D dp_sample_weather -N -e "$2"; }

password_leaked() { # <logfile>
  grep -F -- "$PW_PG" "$1" >/dev/null 2>&1 && return 0
  grep -F -- "$PW_MY" "$1" >/dev/null 2>&1
}

# --- 4: corrupted artifact refused, engine untouched -------------------------
# Runs FIRST among the loader invocations, against an empty artifact cache: a
# later phase's cached copy would mask a corrupted server-side file (the
# loader legitimately serves the cache when its checksum matches), and this
# phase's assertion is that a FIRST-EVER download that mismatches leaves every
# engine untouched.
step "corrupted artifact: refused with no engine touched"
rm -rf "$SRVD/.artifacts"
cp "$ART/pg-trips.dump" "$WORK/pg-trips.dump.good"
printf 'corruption' >> "$ART/pg-trips.dump"
LOG="$WORK/corrupt.log"
if pg_run "$LOG"; then
  bad "a corrupted pg-trips.dump was accepted"
else
  grep -q 'checksum mismatch' "$LOG" \
    && ok "corrupted artifact refused with a checksum mismatch" \
    || bad "corrupted artifact refused, but not by the checksum guard"
fi
db=$(docker exec -e PGPASSWORD=build "$PG" \
  psql -h 127.0.0.1 -U build -d postgres -tAc \
  "SELECT 1 FROM pg_roles WHERE rolname='dp_demo_ro'")
mydb=$(docker exec -e MYSQL_PWD=build "$MY" \
  mysql --protocol=TCP -h 127.0.0.1 -uroot -N -e \
  "SELECT 1 FROM mysql.user WHERE user='dp_demo_ro'" 2>/dev/null || true)
# The scratch Postgres itself creates dp_sample_trips at init (POSTGRES_DB), so
# database existence proves nothing; the demo ROLE is what only the loader
# creates, and a checksum failure must leave it absent on both engines.
if [ "$db" = "1" ] || [ "$mydb" = "1" ]; then
  bad "an engine was touched despite the checksum failure (023 F2 class)"
else
  ok "no demo login exists on either engine — nothing written"
fi
cp "$WORK/pg-trips.dump.good" "$ART/pg-trips.dump"

# --- 2/3: refused inputs -----------------------------------------------------
step "refusals: dollar-quote tag in the password, illegal role name"
LOG="$WORK/refuse-tag.log"
# shellcheck disable=SC2016
# Single quotes ON PURPOSE: the value must carry the literal dollar-quote tag,
# unexpanded, exactly as an operator would type it.
if pg_run "$LOG" -e SAMPLE_PG_PASSWORD='x$sd_pw$y'; then
  bad "a password containing the dollar-quote tag loaded — it must be refused"
else
  if grep -q 'SAMPLE_PG_PASSWORD' "$LOG"; then
    ok "dollar-quote tag in the password refused, naming the variable"
  else
    bad "dollar-quote tag refused but the message does not name SAMPLE_PG_PASSWORD"
  fi
fi
grep -q 'checksum mismatch' "$LOG" && bad "tag refusal happened AFTER download — must refuse before any download"

LOG="$WORK/refuse-role.log"
if pg_run "$LOG" -e SAMPLE_DB_USER='Demo.User'; then
  bad "an illegal role name (Demo.User) was accepted"
else
  if grep -q 'DEMO_USER' "$LOG"; then
    ok "illegal role name refused, naming DEMO_USER"
  else
    bad "illegal role name refused without naming DEMO_USER (confusing die)"
  fi
fi
password_leaked "$WORK/refuse-tag.log" && bad "password value leaked into loader output (refusal path)"
password_leaked "$WORK/refuse-role.log" && bad "password value leaked into loader output (role path)"

# --- 1: adversarial passwords load and authenticate --------------------------
step "adversarial demo passwords: load and authenticate (045 §A)"
LOG="$WORK/load-pg.log"
if pg_run "$LOG" -e SAMPLE_PG_PASSWORD="$PW_PG"; then
  ok "postgres+sqlite loaded with a quote/backslash/dollar password"
else
  bad "postgres load failed with the adversarial SAMPLE_PG_PASSWORD (see $LOG)"
fi
password_leaked "$LOG" && bad "demo password leaked into loader output (postgres path)"

LOG="$WORK/load-my.log"
if my_run "$LOG" -e SAMPLE_MYSQL_PASSWORD="$PW_MY"; then
  ok "mysql loaded with a quote/backslash/dollar password"
else
  bad "mysql load failed with the adversarial SAMPLE_MYSQL_PASSWORD (see $LOG)"
fi
password_leaked "$LOG" && bad "demo password leaked into loader output (mysql path)"

n=$(demo_psql "$PW_PG" "SELECT count(*) FROM trips" 2>/dev/null || true)
[ "$n" = "2" ] && ok "demo login AUTHENTICATES on postgres with the exact adversarial password" \
              || bad "postgres demo login does not authenticate/work with the adversarial password (got: '$n')"
n=$(demo_mysql "$PW_MY" "SELECT count(*) FROM stations" 2>/dev/null || true)
[ "$n" = "1" ] && ok "demo login AUTHENTICATES on mysql with the exact adversarial password" \
              || bad "mysql demo login does not authenticate/work with the adversarial password (got: '$n')"

if demo_psql "$PW_PG" "CREATE TABLE pwn(i INT)" >/dev/null 2>&1; then
  bad "demo login can CREATE on postgres — the SELECT-only grants are broken"
else
  ok "postgres demo login is SELECT-only (CREATE refused)"
fi
if demo_mysql "$PW_MY" "CREATE TABLE pwn(i INT)" >/dev/null 2>&1; then
  bad "demo login can CREATE on mysql — the SELECT-only grants are broken"
else
  ok "mysql demo login is SELECT-only (CREATE refused)"
fi

# --- 5: marker skip and the F2 re-load path ----------------------------------
step "marker skip and re-load over a populated database (F2)"
LOG="$WORK/reskip-pg.log"
if pg_run "$LOG" -e SAMPLE_PG_PASSWORD="$PW_PG" \
   && grep -q 'SKIP' "$LOG"; then
  ok "same-version postgres re-run skips by marker"
else
  bad "same-version postgres re-run did not skip by marker (see $LOG)"
fi
LOG="$WORK/reskip-my.log"
if my_run "$LOG" -e SAMPLE_MYSQL_PASSWORD="$PW_MY" \
   && grep -q 'SKIP' "$LOG"; then
  ok "same-version mysql re-run skips by marker"
else
  bad "same-version mysql re-run did not skip by marker (see $LOG)"
fi

# Simulate the interrupted state F2 was about: tables present, marker absent.
# Guarded so a red run (markers absent because the loads failed) still reaches
# the summary instead of dying here.
docker exec -e PGPASSWORD=build "$PG" \
  psql -h 127.0.0.1 -U build -d dp_sample_trips -qc "DELETE FROM _sample_meta;" >/dev/null 2>&1 || true
docker exec -e MYSQL_PWD=build "$MY" \
  mysql --protocol=TCP -h 127.0.0.1 -uroot -D dp_sample_weather -e "DELETE FROM _sample_meta;" >/dev/null 2>&1 || true

LOG="$WORK/reload-pg.log"
if pg_run "$LOG" -e SAMPLE_PG_PASSWORD="$PW_PG"; then
  ok "postgres re-load over the populated database succeeded (no F2 wedge)"
else
  bad "postgres re-load over the populated database wedged (F2 regression — see $LOG)"
fi
v=$(demo_psql "$PW_PG" "SELECT version FROM _sample_meta" 2>/dev/null || true)
[ "$v" = "v1" ] || bad "postgres marker not restored by the re-load (got: '$v')"

LOG="$WORK/reload-my.log"
if my_run "$LOG" -e SAMPLE_MYSQL_PASSWORD="$PW_MY"; then
  ok "mysql re-load over the populated database succeeded (no F2 wedge)"
else
  bad "mysql re-load over the populated database wedged (F2 regression — see $LOG)"
fi
v=$(demo_mysql "$PW_MY" "SELECT version FROM _sample_meta" 2>/dev/null || true)
[ "$v" = "v1" ] || bad "mysql marker not restored by the re-load (got: '$v')"

n=$(demo_psql "$PW_PG" "SELECT count(*) FROM trips" 2>/dev/null || true)
[ "$n" = "2" ] && ok "postgres data intact after the re-load" || bad "postgres data wrong after re-load (got: '$n')"
n=$(demo_mysql "$PW_MY" "SELECT count(*) FROM stations" 2>/dev/null || true)
[ "$n" = "1" ] && ok "mysql data intact after the re-load" || bad "mysql data wrong after re-load (got: '$n')"

[ -f "$SRVD/nyc_reference.db" ] && ok "sqlite artifact placed" || bad "sqlite artifact not placed"
grep -q '"templates"' "$SRVD/examples-nyc.json" && ok "examples-nyc.json placed" || bad "examples-nyc.json not placed"
[ -f "$SRVD/bootstrap-datasources-nyc.yml" ] && ok "nyc bootstrap datasources file placed" || bad "nyc bootstrap datasources file not placed"

step "selftest complete: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || die "$FAIL assertion(s) failed — logs under $WORK"
log "OK — adversarial passwords load and authenticate; refusals are loud and early; the F2 path holds"
