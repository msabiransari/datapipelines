#!/usr/bin/env bash
# app-sh-clean-test.sh — what `./app.sh --clean` deletes, and what it refuses to.
#
# The verb exists for the owner's clean-slate loop (2026-09-08): stop the project, delete
# its METADATA volume by explicit name, keep the demo source data and the downloaded
# artifacts. Three behaviours, all driven here against the record-and-refuse docker stub
# (scripts/lib/docker-stub.sh, P34 — nothing here reaches a real daemon):
#
#   1. no terminal, no --yes           -> refuses, exit 1, ZERO docker calls
#   2. --yes                           -> `compose … down` with every demo profile, then
#                                         `volume rm <project>-postgres-data` — and ONLY that
#                                         volume; never `down -v`, never mysql-data/sample-data
#   3. --yes, volume already absent    -> down runs, no rm, says so, exit 0
#
#   bash scripts/app-sh-clean-test.sh   # exits 0 when all three hold

set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/lib/docker-stub.sh
source "scripts/lib/docker-stub.sh"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

mkdir -p "$tmp/bin" "$tmp/deploy/env"
cp app.sh "$tmp/app.sh"
cp deploy/env/defaults.env "$tmp/deploy/env/defaults.env"
cp deploy/secrets.env.example "$tmp/deploy/secrets.env.example"
cat >"$tmp/deploy/secrets.env" <<'EOF2'
SPRING_DATASOURCE_PASSWORD=stub
DATAPIPELINES_REDIS_PASSWORD=stub
DATAPIPELINES_JWT_SECRET=stub
DATAPIPELINES_DB_ENCRYPTION_KEY=stub
DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD=stubpw
DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL=admin@local.test
EOF2

ARGV_LOG="$tmp/docker-argv.log"
PROJECT="appcleantest$$"

# $1 = whether the metadata volume exists ("present" | "absent")
make_stub_docker() {
  local vol="$1"
  write_docker_stub "$tmp/bin/docker" "$ARGV_LOG" <<FAKE
if [[ \$* == *" down"* ]]; then exit 0; fi
if [[ \${1:-} == volume && \${2:-} == inspect ]]; then [[ "$vol" == present ]] && exit 0; exit 1; fi
if [[ \${1:-} == volume && \${2:-} == rm ]]; then exit 0; fi
FAKE
}

run_clean() { # args pass through; stdin is /dev/null so there is never a terminal
  (cd "$tmp" && PATH="$tmp/bin:$PATH" APP_COMPOSE_PROJECT="$PROJECT" APP_HOST_PORT=18977 \
    bash app.sh --clean "$@" 2>&1 </dev/null)
}

fail() { echo "app-sh-clean-test: $*" >&2; exit 1; }

# 1. No terminal and no --yes: refuse before touching docker at all.
make_stub_docker present
: >"$ARGV_LOG"
if out=$(run_clean); then
  fail "--clean without --yes and without a terminal must exit 1, but succeeded:
$out"
fi
grep -q "re-run with --yes" <<<"$out" || fail "expected the --yes guidance, got:
$out"
[[ ! -s $ARGV_LOG ]] || fail "a refused --clean must make ZERO docker calls, but the stub recorded:
$(cat "$ARGV_LOG")"

# 2. --yes: down with all three demo profiles, then rm of exactly the metadata volume.
make_stub_docker present
: >"$ARGV_LOG"
out=$(run_clean --yes) || fail "--clean --yes must exit 0, got:
$out"
grep -q "deleted ${PROJECT}-postgres-data" <<<"$out" || fail "expected the deletion line, got:
$out"
down_line=$(grep -E "^compose .* down$" "$ARGV_LOG" || true)
[[ -n $down_line ]] || fail "expected one 'compose … down', recorded:
$(cat "$ARGV_LOG")"
for p in demo-nyc demo-trade demo-lake; do
  grep -q -- "--profile $p" <<<"$down_line" || fail "down must carry --profile $p (the demo one-shots go too), got: $down_line"
done
grep -q -- "-p $PROJECT" <<<"$down_line" || fail "down must name the project, got: $down_line"
grep -qE "down -v|--volumes" "$ARGV_LOG" && fail "NEVER 'down -v' — volumes are removed by explicit name only:
$(cat "$ARGV_LOG")"
grep -qx "volume rm ${PROJECT}-postgres-data" "$ARGV_LOG" || fail "expected 'volume rm ${PROJECT}-postgres-data', recorded:
$(cat "$ARGV_LOG")"
grep -qE "mysql-data|sample-data" "$ARGV_LOG" && fail "--clean must not touch the demo source or artifact volumes:
$(cat "$ARGV_LOG")"
[[ $(grep -c "^volume rm" "$ARGV_LOG") == 1 ]] || fail "exactly one volume rm expected, recorded:
$(cat "$ARGV_LOG")"

# 3. --yes with the volume already gone: down runs, nothing is removed, it says so.
make_stub_docker absent
: >"$ARGV_LOG"
out=$(run_clean --yes) || fail "--clean --yes on a missing volume must still exit 0, got:
$out"
grep -q "did not exist" <<<"$out" || fail "expected the nothing-to-delete line, got:
$out"
grep -q "^volume rm" "$ARGV_LOG" && fail "no rm may be issued for an absent volume:
$(cat "$ARGV_LOG")"

echo "app-sh-clean-test: OK (refuses without --yes; deletes exactly ${PROJECT}-postgres-data; never down -v)"
