#!/usr/bin/env bash
# app-sh-start-test.sh — the T75 test: `./app.sh --start` on a machine whose app
# container is STILL BOOTING when `docker compose up --wait` gives up must say
# "still starting" and exit 0 (the 2026-09-02 rehearsal saw a 243s cold JVM start;
# app.sh reported failure and the stack was healthy 50s later). A container that is
# NOT running keeps the hard failure and exit 1.
#
# The health probe is stubbed to fail: the fake docker below answers `up -d --wait`
# with exit 1 (exactly what the real compose does when the HEALTHCHECK window lapses
# on a slow boot) while `ps` reports the requested container state. app.sh runs from
# a throwaway sandbox so nothing touches a real stack, image, or deploy/.env.
#
#   bash scripts/app-sh-start-test.sh   # exits 0 when both behaviours hold

set -euo pipefail
cd "$(dirname "$0")/.."

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# The sandbox: app.sh cd's to its own directory, so give it one with a deploy/.env
# (scaffold_deploy_env then returns early) and a stub docker first on PATH.
mkdir -p "$tmp/bin" "$tmp/deploy"
cp app.sh "$tmp/app.sh"
cat >"$tmp/deploy/.env" <<'EOF'
METADATA_DB_PASSWORD=stub
REDIS_PASSWORD=stub
JWT_SECRET=stub
ENCRYPTION_KEY=stub
EOF

# $1 = the State the app container reports ("running" | "exited")
make_stub_docker() {
  cat >"$tmp/bin/docker" <<STUB
#!/usr/bin/env bash
if [[ \$3 == inspect ]]; then exit 0; fi                        # --no-build image check
if [[ \$* == *"up -d --wait"* ]]; then exit 1; fi               # health probe: FAILS (the T75 premise)
if [[ \$* == *"ps --format json"* ]]; then
  echo '[{"Service":"datapipelines","State":"$1"}]'
  exit 0
fi
exit 0                                                          # logs, anything else
STUB
  chmod +x "$tmp/bin/docker"
}

run_start() { (cd "$tmp" && PATH="$tmp/bin:$PATH" bash app.sh --start --no-build 2>&1); }

fail() { echo "app-sh-start-test: $*" >&2; exit 1; }

# 1. Container still running after the wait gave up -> "still starting", exit 0.
make_stub_docker "running"
out=$(run_start) || fail "a still-booting container must exit 0, got:
$out"
grep -q "still starting" <<<"$out" || fail "expected the 'still starting' message, got:
$out"
grep -q "app.sh --status" <<<"$out" || fail "the message must point at ./app.sh --status, got:
$out"

# 2. Container gone (crashed/exited) -> the hard failure stands, exit 1.
make_stub_docker "exited"
if out=$(run_start); then
  fail "an exited container must exit 1, but --start succeeded:
$out"
fi
grep -q "stack did not become healthy" <<<"$out" || fail "expected the hard-failure message, got:
$out"

echo "app-sh-start-test: OK (still-booting exits 0 with guidance; dead container exits 1)"
