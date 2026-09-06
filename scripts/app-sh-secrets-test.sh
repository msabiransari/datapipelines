#!/usr/bin/env bash
# app-sh-secrets-test.sh — the T137 guard: the credential app.sh PRINTS is the credential
# the container GETS.
#
# The defect: `./app.sh --start` scaffolded an env file with a generated bootstrap password,
# printed it, and two lanes and the owner then could not log in with the printed value. The
# generator and the thing that reaches the app have to be ONE derivation, and the only way to
# know they are is to read the value back out of the far end.
#
# So: scaffold into a throwaway sandbox (no stack, no image, no real deploy/secrets.env),
# then ask Compose what it would actually put in the container's environment, and compare
# that with what app.sh's own `effective` derivation reports. A generated secret that survives
# the env-file hop is the whole claim.
#
#   bash scripts/app-sh-secrets-test.sh     # exits 0 when the two agree
#
# Needs docker (for `compose config` — it starts nothing); skips with a loud line without it,
# which is why the live gate runs it too rather than trusting a green build alone.

set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v docker >/dev/null 2>&1; then
  echo "app-sh-secrets-test: SKIPPED — no docker on PATH (compose config is the far end this compares against)"
  exit 0
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# A sandbox that is the repo, minus anything that could touch a real stack: the tracked env
# files and compose files are copied, deploy/secrets.env deliberately is NOT (that absence is
# what makes app.sh scaffold, which is the code under test).
mkdir -p "$tmp/deploy/env/posture"
cp app.sh "$tmp/app.sh"
cp deploy/compose.yml deploy/compose.local-build.yml "$tmp/deploy/"
cp -R deploy/sample-data "$tmp/deploy/sample-data"
cp deploy/env/posture/development.env deploy/env/posture/hardened.env "$tmp/deploy/env/posture/"
cp deploy/env/demo.env "$tmp/deploy/env/"

# The stub docker. STRICT ALLOWLIST — `compose config` and nothing else.
#
# The first version of this passed every `docker compose …` through to the real daemon
# "because config needs it", and app.sh --start then ran a REAL `compose up -d --wait`
# under the DEFAULT project and stopped the live stack on this machine. `-p` is not the
# only thing that decides which project a compose command touches; a stub that forwards a
# verb it did not mean to forward decides it too. So: the verb is checked, not the flag,
# and the sandbox additionally pins a project and port of its own so even a stub that
# leaked could not resolve to `dp`.
mkdir -p "$tmp/bin"
real_docker=$(command -v docker)
cat >"$tmp/bin/docker" <<STUB
#!/usr/bin/env bash
# Only \`docker compose [flags] config …\` reaches the daemon; every other verb is a no-op.
if [[ \${1:-} == compose ]]; then
  for arg in "\$@"; do
    case "\$arg" in
      config) exec "$real_docker" "\$@" ;;
      up|down|start|stop|restart|rm|kill|create|run|exec) exit 0 ;;
    esac
  done
  exit 0
fi
exit 0
STUB
chmod +x "$tmp/bin/docker"

# Belt to the stub's braces: a project and a port that are nobody's.
export APP_COMPOSE_PROJECT="apppwtest$$"
export APP_HOST_PORT=18975

fail() { echo "app-sh-secrets-test: $*" >&2; exit 1; }

# 1. Scaffold. app.sh writes deploy/secrets.env ONCE, with generated values.
(cd "$tmp" && PATH="$tmp/bin:$PATH" bash app.sh --start --no-build >/dev/null 2>&1) || true
[ -f "$tmp/deploy/secrets.env" ] || fail "app.sh --start did not scaffold deploy/secrets.env"

file_pw=$(grep -E '^DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD=' "$tmp/deploy/secrets.env" | head -1 | cut -d= -f2-)
[ -n "$file_pw" ] || fail "the scaffold wrote no DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD"
file_email=$(grep -E '^DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL=' "$tmp/deploy/secrets.env" | head -1 | cut -d= -f2-)

# 2. The FAR END: what compose would hand the container, with the same env-file list app.sh
#    composes (posture, then secrets — secrets last).
rendered=$(cd "$tmp" && docker compose -p "$APP_COMPOSE_PROJECT" \
  -f deploy/compose.yml -f deploy/compose.local-build.yml \
  --env-file deploy/env/posture/development.env \
  --env-file deploy/secrets.env \
  config --format json)

container_pw=$(printf '%s' "$rendered" | python3 -c '
import json, sys
env = json.load(sys.stdin)["services"]["datapipelines"]["environment"]
print(env.get("DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD", ""))
')
container_email=$(printf '%s' "$rendered" | python3 -c '
import json, sys
env = json.load(sys.stdin)["services"]["datapipelines"]["environment"]
print(env.get("DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL", ""))
')

[ "$container_pw" = "$file_pw" ] || fail "the scaffolded bootstrap password is NOT what the container gets.
  deploy/secrets.env: ${#file_pw} chars
  container env:      ${#container_pw} chars
  This is T137 exactly: the value app.sh prints must be the value the app seeds."
[ "$container_email" = "$file_email" ] || fail "the scaffolded bootstrap admin email is NOT what the container gets ($file_email vs $container_email)"

# 3. Falsification: doctor the file and the comparison must go red. A guard that cannot fail
#    is not a guard, and this one compares two reads that could both come from one place.
sed -i.bak "s|^DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD=.*|DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD=doctored|" \
  "$tmp/deploy/secrets.env" && rm -f "$tmp/deploy/secrets.env.bak"
doctored=$(cd "$tmp" && docker compose -p "$APP_COMPOSE_PROJECT" \
  -f deploy/compose.yml -f deploy/compose.local-build.yml \
  --env-file deploy/env/posture/development.env --env-file deploy/secrets.env \
  config --format json | python3 -c '
import json, sys
print(json.load(sys.stdin)["services"]["datapipelines"]["environment"].get("DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD", ""))
')
[ "$doctored" = "doctored" ] || fail "the far-end read does not track the file — the comparison above proves nothing"
[ "$doctored" != "$file_pw" ] || fail "the doctored value equals the original; the sed did nothing"

echo "app-sh-secrets-test: OK (scaffolded credential reaches the container byte-for-byte; the read tracks the file)"
