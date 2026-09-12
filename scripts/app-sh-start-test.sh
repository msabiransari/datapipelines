#!/usr/bin/env bash
# app-sh-start-test.sh — what `./app.sh --start` does while the app is still booting, and
# what it does when the app is dead.
#
# T75 (075): `docker compose up --wait` gives up on the image HEALTHCHECK's schedule, and
# app.sh used to report that deadline as the answer — the 2026-09-02 rehearsal saw a 243s
# cold JVM start, and the owner's own first boot printed "still starting" at ~2 minutes
# for a stack that was healthy a minute later.
#
# 081 stopped reading `up --wait`'s verdict at all: app.sh polls /health until it answers,
# the app container stops running, or a bounded patience runs out. Three behaviours, and
# this test drives all three:
#
#   1. container running, /health never answers -> the bound is reached, guidance, exit 0
#   2. container exited                          -> hard failure naming the container, exit 1
#   3. /health answers                           -> "UP", the login, exit 0
#   6. --demo, every loader exited 0             -> "demo data loaded", exit 0
#   7. --demo, one loader exited 1               -> the loader named, its logs, exit 1,
#                                                   and NO "demo data loaded" line
#      (2026-09-12: a stale MySQL root password failed both MySQL loaders and --start
#      printed success — `up --wait`'s verdict is discarded on purpose, so the loaders'
#      exit codes have to be read explicitly)
#
# THE STUB REFUSES (P34). scripts/lib/docker-stub.sh records every invocation and exits 97
# on anything the test did not explicitly fake — no fallthrough to the real binary, which
# is how the 075 version of the sibling test stopped the owner's live stack for 12 minutes.
# Case 4 below shows the refusal firing on a `compose up` rather than asserting it.
#
#   bash scripts/app-sh-start-test.sh   # exits 0 when all four hold

set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/lib/docker-stub.sh
source "scripts/lib/docker-stub.sh"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# The sandbox: app.sh cd's to its own directory, so give it one with the files it reads —
# the tracked settings file it refuses to start without, and a deploy/secrets.env so the
# scaffold returns early. 075: both are COPIED from the repo rather than written here, so
# a change to the real ones cannot leave this harness passing against a shape that is gone.
mkdir -p "$tmp/bin" "$tmp/deploy/env"
cp app.sh "$tmp/app.sh"
cp deploy/env/defaults.env "$tmp/deploy/env/defaults.env"
cp deploy/secrets.env.example "$tmp/deploy/secrets.env.example"
cat >"$tmp/deploy/secrets.env" <<'EOF'
SPRING_DATASOURCE_PASSWORD=stub
DATAPIPELINES_REDIS_PASSWORD=stub
DATAPIPELINES_JWT_SECRET=stub
DATAPIPELINES_DB_ENCRYPTION_KEY=stub
DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD=stubpw
DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL=admin@local.test
EOF

ARGV_LOG="$tmp/docker-argv.log"

# $1 = the State the app container reports ("running" | "exited")
# $2 = the psql row seeded_admin() should see ("" = none)
# $3 = the exit code every demo LOADER reports to `ps -a --format json <service>` ("" = 0).
#      Answered in the NDJSON shape compose v2.21+ emits (one object per line), with the
#      service name echoed back, so the check is proven against the real wire format.
make_stub_docker() {
  local state="$1" row="${2:-}" loader_exit="${3:-0}"
  write_docker_stub "$tmp/bin/docker" "$ARGV_LOG" <<FAKE
# --no-build image check: \`docker image inspect <tag>\`. The 075 stub tested \$3 here,
# which never matched — it worked only because that stub's catch-all was a silent
# \`exit 0\`. With a stub that REFUSES, a wrong fake is a visible failure, which is
# the point of P34.
if [[ \${1:-} == image && \${2:-} == inspect ]]; then exit 0; fi
# the health probe: FAILS, exactly as compose does when the HEALTHCHECK window lapses
if [[ \$* == *"up -d --wait"* ]]; then exit 1; fi
if [[ \$* == *"ps -a --format json"* ]]; then
  # the last argv element is the loader service being asked about
  echo "{\"Service\":\"\${@: -1}\",\"State\":\"exited\",\"ExitCode\":$loader_exit}"
  exit 0
fi
if [[ \$* == *"ps --format json"* ]]; then
  echo '[{"Service":"datapipelines","State":"$state"}]'
  exit 0
fi
if [[ \$* == *"exec -T postgres"* ]]; then printf '%s' '$row'; exit 0; fi
if [[ \$* == *"logs"* ]]; then echo '(stub: no logs)'; exit 0; fi
FAKE
}

# A curl that answers /health only when the test says so, so case 3 is reachable without
# a container. app.sh's only health signal is `curl -sf "$HEALTH_URL"`.
make_stub_curl() { # $1 = "up" | "down"
  cat >"$tmp/bin/curl" <<CURL
#!/usr/bin/env bash
[[ "$1" == up ]] && exit 0
exit 22
CURL
  chmod +x "$tmp/bin/curl"
}

run_start() {
  (cd "$tmp" && PATH="$tmp/bin:$PATH" APP_COMPOSE_PROJECT="appstarttest$$" APP_HOST_PORT=18976 \
    HEALTH_WAIT_SECONDS="${1:-10}" bash app.sh --start --no-build 2>&1)
}

run_start_demo() { # $1 = the --demo families
  (cd "$tmp" && PATH="$tmp/bin:$PATH" APP_COMPOSE_PROJECT="appstarttest$$" APP_HOST_PORT=18976 \
    HEALTH_WAIT_SECONDS=10 bash app.sh --start --no-build --demo "$1" 2>&1)
}

fail() { echo "app-sh-start-test: $*" >&2; exit 1; }

# 1. Container still running, health never answers -> the bound, guidance, exit 0.
make_stub_docker "running"
make_stub_curl down
out=$(run_start 10) || fail "a still-booting container must exit 0, got:
$out"
grep -q "STILL starting" <<<"$out" || fail "expected the bounded-wait message, got:
$out"
grep -q "still booting (0s" <<<"$out" || fail "expected a progress line while waiting, got:
$out"
grep -q "app.sh --status" <<<"$out" || fail "the message must point at ./app.sh --status, got:
$out"

# 2. Container gone (crashed/exited) -> the hard failure, exit 1, and it must arrive
#    without burning the whole bound: the loop checks liveness on every pass.
make_stub_docker "exited"
if out=$(run_start 300); then
  fail "an exited container must exit 1, but --start succeeded:
$out"
fi
grep -q "app container is not running" <<<"$out" || fail "expected the hard-failure message, got:
$out"

# 3. Health answers -> UP, and the login that the DATABASE reports (T137).
make_stub_docker "running" "admin@local.test|t"
make_stub_curl up
out=$(run_start 10) || fail "a healthy stack must exit 0, got:
$out"
grep -q "UP — http://localhost:18976" <<<"$out" || fail "expected the UP line, got:
$out"
grep -q "LOGIN: admin@local.test / stubpw" <<<"$out" \
  || fail "a healthy --start must print the seeded login and its one-time password, got:
$out"

# 5. A machine with NO deploy/secrets.env: --start scaffolds it AND says the one-time
#    admin credential is seeded at THIS boot and no later one. The owner started without
#    scaffolding, got demo-admin@demo.local seeded, and no later edit could move it.
rm -f "$tmp/deploy/secrets.env"
make_stub_docker "running" ""
make_stub_curl up
out=$(run_start 10) || fail "--start on a machine with no secrets.env must still work, got:
$out"
[ -f "$tmp/deploy/secrets.env" ] || fail "--start did not scaffold the missing deploy/secrets.env"
grep -q "FIRST START" <<<"$out" || fail "--start must say the seed fires at THIS boot, got:
$out"
grep -q "app.sh --scaffold" <<<"$out" \
  || fail "the first-start notice must point at --scaffold, which is how the address is chosen
  BEFORE the seed. Got:
$out"

# 6. --demo nyc,trade with every loader exited 0 -> the demo line, exit 0, and the check
#    asked about EVERY loader of both families (four services), with -a.
make_stub_docker "running" "admin@local.test|t" 0
make_stub_curl up
out=$(run_start_demo nyc,trade) || fail "a demo start whose loaders all exited 0 must exit 0, got:
$out"
grep -q "demo data loaded (nyc,trade)" <<<"$out" || fail "expected the demo line, got:
$out"
for svc in sample-data sample-data-mysql sample-data-trade sample-data-trade-mysql; do
  grep -q "ps -a --format json $svc\$" "$ARGV_LOG" \
    || fail "the loader check never asked compose about $svc (with -a; exited one-shots are hidden without it)"
done

# 7. --demo nyc with a loader exited 1 -> exit 1, the loader named, its logs shown, and
#    NOT the demo line. The app is healthy throughout: health alone must not be the verdict.
make_stub_docker "running" "admin@local.test|t" 1
make_stub_curl up
if out=$(run_start_demo nyc); then
  fail "a demo start with a failed loader must exit 1, but --start succeeded:
$out"
fi
grep -q "demo data NOT loaded" <<<"$out" || fail "expected the loader failure message, got:
$out"
grep -q "sample-data-mysql (exited, exit 1)" <<<"$out" || fail "the failing loader must be NAMED with its exit code, got:
$out"
grep -q "UP — http://localhost:18976" <<<"$out" || fail "the app was healthy; the UP line must still be reported, got:
$out"
grep -q "(stub: no logs)" <<<"$out" || fail "the failing loader's logs must be shown, got:
$out"
if grep -q "demo data loaded (" <<<"$out"; then
  fail "the success line was printed over a failed loader:
$out"
fi

# 4. THE REFUSAL (P34). Every argv the run issued is in the log; the ones the fakes above
#    do not answer must exit non-zero. `compose up` WITHOUT `--wait` is the shape that took
#    the owner's stack down — the 075 stub answered it with a silent `exit 0` after
#    forwarding it to the real daemon.
if (cd "$tmp" && PATH="$tmp/bin:$PATH" bash bin/docker compose -p dp up -d) 2>"$tmp/refusal.txt"; then
  fail "the stub ACCEPTED 'docker compose -p dp up -d' — a stub that does not refuse is the real binary"
fi
grep -q "REFUSED an invocation the test did not fake" "$tmp/refusal.txt" \
  || fail "the stub exited non-zero but said nothing; the refusal must be loud. Got:
$(cat "$tmp/refusal.txt")"
grep -q "compose -p dp up -d" "$ARGV_LOG" || fail "the stub did not RECORD the refused argv"
echo "app-sh-start-test: the stub's refusal, verbatim —"
sed 's/^/    /' "$tmp/refusal.txt"

echo "app-sh-start-test: OK (bounded wait exits 0 with guidance; dead container exits 1;"
echo "                       a healthy boot prints the seeded login; a first start scaffolds and"
echo "                       says the seed fires now; demo loaders are read back — all 0 says"
echo "                       loaded, one non-zero names it and exits 1; the stub refuses and records)"
