#!/usr/bin/env bash
# app-sh-secrets-test.sh — two guards over deploy/secrets.env, which is the only file in
# the deployment that no one can read from the repository.
#
#   T137: the credential app.sh PRINTS is the credential the container GETS. The scaffold
#         once generated a bootstrap password, printed it, and a different value reached
#         the app; two lanes and the owner could not log in. The generator and the far end
#         have to be ONE derivation, and the only way to know they are is to read the
#         value back out of the far end — `compose config`, which starts nothing.
#
#   081:  the scaffold is GENERATED FROM deploy/secrets.env.example, so the two cannot
#         drift. 075 kept them as two hand-written lists and the template silently omitted
#         five keys the scaffold wrote (SAMPLE_PG_PASSWORD, SAMPLE_MYSQL_PASSWORD,
#         SAMPLE_MYSQL_ROOT_PASSWORD, DATAPIPELINES_AUTH_LOCAL_ENABLED,
#         DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD) — a deployer who followed the
#         documented `cp` path got a file that could not run the demo. This test diffs the
#         two NAME sets, active and commented alike.
#
#   bash scripts/app-sh-secrets-test.sh     # exits 0 when both hold
#
# THE STUB REFUSES (P34). scripts/lib/docker-stub.sh records every invocation and exits 97
# on anything not explicitly faked. `compose config` is the ONE allowlisted verb, named
# here by hand because it renders a model and starts nothing. The 075 version of this file
# forwarded every unrecognised verb instead, `--start` issued a real `compose up -d --wait`
# under the DEFAULT project, and the owner's live stack was down for twelve minutes.
#
# Needs docker (for `compose config`); skips with a loud line without it, which is why the
# live gate runs it too rather than trusting a green build alone.

set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/lib/docker-stub.sh
source "scripts/lib/docker-stub.sh"

if ! command -v docker >/dev/null 2>&1; then
  echo "app-sh-secrets-test: SKIPPED — no docker on PATH (compose config is the far end this compares against)"
  exit 0
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# A sandbox that is the repo, minus anything that could touch a real stack: the tracked
# files are copied, deploy/secrets.env deliberately is NOT (that absence is what makes
# app.sh scaffold, which is the code under test).
mkdir -p "$tmp/deploy/env" "$tmp/scripts/lib"
cp app.sh "$tmp/app.sh"
cp scripts/lib/docker-stub.sh "$tmp/scripts/lib/"
cp deploy/compose.yml deploy/compose.local-build.yml "$tmp/deploy/"
cp -R deploy/sample-data "$tmp/deploy/sample-data"
cp deploy/env/defaults.env "$tmp/deploy/env/"
cp deploy/secrets.env.example "$tmp/deploy/"

ARGV_LOG="$tmp/docker-argv.log"
write_docker_stub "$tmp/bin/docker" "$ARGV_LOG" config <<'FAKE'
FAKE

# Belt to the stub's braces: a project and a port that are nobody's.
export APP_COMPOSE_PROJECT="apppwtest$$"
export APP_HOST_PORT=18975

fail() { echo "app-sh-secrets-test: $*" >&2; exit 1; }

# 1. Scaffold, via the verb that exists for it. `--scaffold` writes the file and stops, so
#    the admin email and the OIDC client can be set BEFORE the one-time seed fires.
(cd "$tmp" && PATH="$tmp/bin:$PATH" bash app.sh --scaffold >"$tmp/scaffold.out" 2>&1) \
  || fail "app.sh --scaffold failed:
$(cat "$tmp/scaffold.out")"
[ -f "$tmp/deploy/secrets.env" ] || fail "app.sh --scaffold did not write deploy/secrets.env"
grep -q "DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL" "$tmp/scaffold.out" \
  || fail "--scaffold must say that the admin address is editable before the first start"
[ "$(stat -f '%Lp' "$tmp/deploy/secrets.env" 2>/dev/null || stat -c '%a' "$tmp/deploy/secrets.env")" = "600" ] \
  || fail "deploy/secrets.env must be mode 600 — it holds every credential"

# 1b. ONE DERIVATION: the scaffold names exactly what the template names, active lines and
#     commented lines alike. Comparing only the ACTIVE keys would miss the whole 075
#     defect, whose omitted keys were commented in one file and generated in the other.
names() { grep -Eo '^#? *[A-Z][A-Z0-9_]*=' "$1" | tr -d '# ' | tr -d '=' | sort -u; }
if ! diff -u <(names "$tmp/deploy/secrets.env.example") <(names "$tmp/deploy/secrets.env") >"$tmp/keys.diff"; then
  fail "the scaffolded deploy/secrets.env and deploy/secrets.env.example name different
  variables — the scaffold is supposed to be GENERATED from the template (081 §A):
$(cat "$tmp/keys.diff")"
fi

# 1c. Every secret the template declares must have come out with a VALUE, or the scaffold
#     writes a file that cannot boot. (Promotion keys stay empty on purpose: an empty
#     server key is how a deployment refuses inbound promotion.)
for key in DATAPIPELINES_JWT_SECRET DATAPIPELINES_DB_ENCRYPTION_KEY SPRING_DATASOURCE_PASSWORD \
           DATAPIPELINES_REDIS_PASSWORD DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD \
           SAMPLE_PG_PASSWORD SAMPLE_MYSQL_PASSWORD SAMPLE_MYSQL_ROOT_PASSWORD; do
  value=$(grep -E "^$key=" "$tmp/deploy/secrets.env" | head -1 | cut -d= -f2-)
  [ -n "$value" ] || fail "the scaffold left $key empty — a deployment cannot boot without it"
done
grep -q '^DATAPIPELINES_AUTH_LOCAL_ENABLED=true' "$tmp/deploy/secrets.env" \
  || fail "the scaffold must turn local accounts on, or a clean machine with no OIDC client
  never becomes healthy (ConfigValidator §7 requires one authentication method)"

file_pw=$(grep -E '^DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD=' "$tmp/deploy/secrets.env" | head -1 | cut -d= -f2-)
file_email=$(grep -E '^DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL=' "$tmp/deploy/secrets.env" | head -1 | cut -d= -f2-)

# 2. The FAR END: what compose would hand the container, with the same env-file list app.sh
#    composes — defaults.env, then secrets.env. Secrets last.
rendered=$(cd "$tmp" && docker compose -p "$APP_COMPOSE_PROJECT" \
  -f deploy/compose.yml -f deploy/compose.local-build.yml \
  --env-file deploy/env/defaults.env \
  --env-file deploy/secrets.env \
  config --format json)

container_value() { printf '%s' "$rendered" | python3 -c '
import json, sys
env = json.load(sys.stdin)["services"]["datapipelines"]["environment"]
print(env.get(sys.argv[1], ""))
' "$1"; }

container_pw=$(container_value DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD)
container_email=$(container_value DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL)

[ "$container_pw" = "$file_pw" ] || fail "the scaffolded bootstrap password is NOT what the container gets.
  deploy/secrets.env: ${#file_pw} chars
  container env:      ${#container_pw} chars
  This is T137 exactly: the value app.sh prints must be the value the app seeds."
[ "$container_email" = "$file_email" ] || fail "the scaffolded bootstrap admin email is NOT what the container gets ($file_email vs $container_email)"

# 2b. 081 §B, at the far end: with no posture key set anywhere, the container must not
#     receive a VALUE for one — an empty string outranks the profile that carries the
#     posture's default, which is how a `hardened` stack booted with authoring=on.
#     `config` renders the valueless form as JSON null, and a null environment entry is
#     the one Compose does not put in the container (measured: `${VAR:-}` and
#     `${VAR:+${VAR}}` both render "" and ARE set; `VAR:` renders null and is not).
posture_keys=$(printf '%s' "$rendered" | python3 -c '
import json, sys
env = json.load(sys.stdin)["services"]["datapipelines"]["environment"]
print(",".join(f"{k}={env[k]!r}" for k in ("DATAPIPELINES_AUTH_COOKIE_SECURE",
                                           "DATAPIPELINES_DEPLOYMENT_AUTHORING_ENABLED")
               if env.get(k) is not None))
')
[ -z "$posture_keys" ] || fail "compose gives the container $posture_keys even though no file sets
  it — the profile's posture default is then dead. deploy/compose.yml must pass those two
  in the valueless form (\`      DATAPIPELINES_AUTH_COOKIE_SECURE:\`, nothing after the colon)."

# 3. Falsification: doctor the file and the comparison must go red. A guard that cannot
#    fail is not a guard, and this one compares two reads that could both come from one place.
sed -i.bak "s|^DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD=.*|DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD=doctored|" \
  "$tmp/deploy/secrets.env" && rm -f "$tmp/deploy/secrets.env.bak"
doctored=$(cd "$tmp" && docker compose -p "$APP_COMPOSE_PROJECT" \
  -f deploy/compose.yml -f deploy/compose.local-build.yml \
  --env-file deploy/env/defaults.env --env-file deploy/secrets.env \
  config --format json | python3 -c '
import json, sys
print(json.load(sys.stdin)["services"]["datapipelines"]["environment"].get("DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD", ""))
')
[ "$doctored" = "doctored" ] || fail "the far-end read does not track the file — the comparison above proves nothing"
[ "$doctored" != "$file_pw" ] || fail "the doctored value equals the original; the sed did nothing"

# 4. THE REFUSAL (P34), shown rather than claimed: `compose up` is not `compose config`.
if (cd "$tmp" && PATH="$tmp/bin:$PATH" bash bin/docker compose -p dp up -d --wait) 2>"$tmp/refusal.txt"; then
  fail "the stub ACCEPTED 'docker compose -p dp up -d --wait' — this is the exact invocation
  that took the owner's live stack down in 075"
fi
grep -q "REFUSED an invocation the test did not fake" "$tmp/refusal.txt" \
  || fail "the stub exited non-zero but said nothing; the refusal must be loud"
echo "app-sh-secrets-test: the stub's refusal, verbatim —"
sed 's/^/    /' "$tmp/refusal.txt"

echo "app-sh-secrets-test: OK ($(names "$tmp/deploy/secrets.env" | wc -l | tr -d ' ') variable names, scaffold == template;"
echo "                         the generated credential reaches the container byte-for-byte;"
echo "                         the posture keys reach it not at all; the stub refuses \`compose up\`)"
