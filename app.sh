#!/usr/bin/env bash
# Local runner — the deployment.md Appendix A stack, entirely in Docker.
# No local Java or Gradle: the jar is built by the repo's pinned Gradle wrapper
# inside a throwaway JDK container, packaged with the repo Dockerfile, and run
# via deploy/compose.yml + deploy/compose.local-build.yml — the same stack an
# engineer evaluating the project runs.
#
#   ./app.sh --start [--env <label>] [--posture development|hardened]
#            [--demo nyc,trade] [--no-build]
#   ./app.sh --stop [--demo <families>]   stop the stack, demo services included
#   ./app.sh --status [--demo <families>] show services + app health
#   ./app.sh --logs                       follow the app container's logs
#
# THE TWO VARIABLES (docs/environments.md). --env is the ORG's label for this
# deployment (default `local`); --posture is the PRODUCT's stance, `development`
# or `hardened`. Only the environment named `local` gets a posture for free —
# any other name with no posture refuses to start, naming both variables. This
# script is a LOADER: everything it does is assemble env files that work just as
# well under `java -jar`, systemd, Kubernetes, ECS and Nomad.
#
#   deploy/env/posture/<posture>.env   tracked, non-secret: the posture
#   deploy/env/demo.env                tracked: sample-data versions and posture
#   deploy/secrets.env                 GIT-IGNORED: every secret. Scaffolded here.
#
# ...passed in that order, secrets LAST, so a value an operator sets in
# deploy/secrets.env always wins. deploy/env/example.env is the full reference.
#
# --demo is a FLAG, not an environment (`--demo nyc`, `--demo nyc,trade`): it sets
# DATAPIPELINES_DEMO, from which this script derives the compose profiles that gate
# the one-shot loader services and the ON markers the bootstrap lists are built from.
# Either family implies the -Pmysql jar build (MySQL Connector/J is NOT in the
# default build — GPL + FOSS exception, datasources.md §10.2 — and the weather and
# Comtrade datasources would otherwise fail registration with
# datasource.driver_not_loaded). The `hardened` posture REFUSES demo at boot.
#
# The Gradle cache persists in ./.gradle-docker (git-ignored), so only the first
# build is cold.

set -euo pipefail
cd "$(cd "$(dirname "$0")" && pwd)"

SECRETS_ENV="deploy/secrets.env"
DEMO_ENV="deploy/env/demo.env"
POSTURE_DIR="deploy/env/posture"

die() { echo "app.sh: $*" >&2; exit 1; }

# ---------------------------------------------------------------- arguments
# --env / --posture / --demo are MODES, not subcommands: they change the compose
# invocation for every verb, so they are stripped from the argument list here
# rather than inside start(). Without the demo families the demo services are
# invisible to compose: --status cannot show them, and --stop would leave this
# project's demo MySQL running — stop() compensates for that explicitly (045 §C.1).
DP_ENV="${DATAPIPELINES_ENV:-local}"
DP_POSTURE="${DATAPIPELINES_POSTURE:-}"
DP_DEMO="${DATAPIPELINES_DEMO:-}"
ARGS=()
while (($#)); do
  case "$1" in
    --env) shift; [[ ${1:-} ]] || die "--env needs a value (your org's name for this deployment)"; DP_ENV="$1" ;;
    --posture) shift; [[ ${1:-} ]] || die "--posture needs a value: development or hardened"; DP_POSTURE="$1" ;;
    --demo) shift; [[ ${1:-} ]] || die "--demo needs a family list, e.g. --demo nyc or --demo nyc,trade"; DP_DEMO="$1" ;;
    --demo-nyc|--demo-trade)
      die "$1 is gone (075). Demo is a FLAG now, one variable for every loader:
    ./app.sh --start --demo nyc
    ./app.sh --start --demo nyc,trade
  It sets DATAPIPELINES_DEMO, which docs/environments.md documents; the compose
  profiles are derived from it." ;;
    *) ARGS+=("$1") ;;
  esac
  shift
done
set -- "${ARGS[@]:-}"

# The posture is REQUIRED for any environment but `local` — the same rule the app's
# ConfigValidator enforces, stated here so the failure arrives before a container does.
if [[ -z $DP_POSTURE ]]; then
  if [[ $DP_ENV == local ]]; then
    DP_POSTURE=development
  else
    die "--env $DP_ENV needs a --posture: development or hardened.
  The environment's NAME is yours; the POSTURE is the product's stance, and nothing
  branches on the name. Only the environment named 'local' gets a posture for free.
  See docs/environments.md."
  fi
fi
case "$DP_POSTURE" in
  development|hardened) ;;
  *) die "--posture $DP_POSTURE is not a posture: development or hardened (docs/environments.md)." ;;
esac
POSTURE_ENV="$POSTURE_DIR/$DP_POSTURE.env"
[[ -f $POSTURE_ENV ]] || die "$POSTURE_ENV is missing — the posture files are tracked in this repo."

DEMO_FAMILIES=$(printf '%s' "$DP_DEMO" | tr -d '[:space:]')
DEMO_NYC=0
DEMO_TRADE=0
if [[ -n $DEMO_FAMILIES ]]; then
  IFS=',' read -r -a _families <<<"$DEMO_FAMILIES"
  for f in "${_families[@]}"; do
    case "$f" in
      nyc) DEMO_NYC=1 ;;
      trade) DEMO_TRADE=1 ;;
      "") ;;
      *) die "--demo: unknown sample-data family '$f'. The families are nyc and trade." ;;
    esac
  done
  [[ $DP_POSTURE == hardened ]] && die "--demo is refused under the hardened posture: demo registers sample
  datasources and seeds example content, which is out-of-the-box EVALUATION, not a
  hardened deployment. Run it under --posture development (docs/environments.md)."
fi

# ---------------------------------------------------------------- lane knobs
# APP_COMPOSE_PROJECT overrides the compose project (default: the files' pinned
# "dp") — for running a second isolated copy on one machine (CI, rehearsals). It is
# passed as `-p`, which also sets COMPOSE_PROJECT_NAME for interpolation, so the
# volume names in deploy/compose.yml scope to the lane with the flag alone (T116).
COMPOSE_PROJECT="${APP_COMPOSE_PROJECT:-dp}"
# The host port CANNOT be derived from the project name — a port is a number — so a
# second isolated copy must be given one, or it binds 8080 on top of the first stack
# (072 §F2: a raw `compose -p dp072 up` replaced the live app for ten minutes).
if [[ $COMPOSE_PROJECT != dp && -z ${APP_HOST_PORT:-} ]]; then
  die "APP_COMPOSE_PROJECT=$COMPOSE_PROJECT is a SECOND isolated copy, and it has no
  APP_HOST_PORT. Without one it publishes 8080 — the default project's port — and
  replaces the stack already there. Export a free port:
    export APP_COMPOSE_PROJECT=$COMPOSE_PROJECT APP_HOST_PORT=<free port>"
fi
APP_HOST_PORT="${APP_HOST_PORT:-8080}"
export APP_HOST_PORT
APP_URL="http://localhost:${APP_HOST_PORT}"
HEALTH_URL="${APP_URL}/health"

# The image tag follows the compose project (default "dp"): a hardcoded single tag
# meant every lane's --start rebuilt the tag every OTHER lane's stack resolves (034
# F2). IMAGE_TAG in the environment overrides the derivation entirely.
if [[ -z ${IMAGE_TAG:-} ]]; then
  if [[ $COMPOSE_PROJECT == dp ]]; then IMAGE_TAG="datapipelines:local"; else IMAGE_TAG="datapipelines:local-${COMPOSE_PROJECT}"; fi
fi
export IMAGE_TAG
BUILDER_IMAGE="eclipse-temurin:21-jdk"
GRADLE_CACHE="$PWD/.gradle-docker"

# The env-file list, in precedence order (compose: later files win). Every verb uses
# the same list so `--status` and `--stop` see exactly the stack `--start` created.
COMPOSE=(docker compose -p "$COMPOSE_PROJECT"
  -f deploy/compose.yml -f deploy/compose.local-build.yml
  --env-file "$POSTURE_ENV")
((DEMO_NYC || DEMO_TRADE)) && COMPOSE+=(--env-file "$DEMO_ENV")
COMPOSE+=(--env-file "$SECRETS_ENV")
((DEMO_NYC)) && COMPOSE+=(--profile demo-nyc)
((DEMO_TRADE)) && COMPOSE+=(--profile demo-trade)

# The variables the compose file interpolates that are NOT in any env file: the two
# that ARE the invocation, and the ON markers the bootstrap lists are built from.
# Exported (not written to a file) because they are this invocation's flags — a
# family switched off later must take effect without hand-editing anything.
export DATAPIPELINES_ENV="$DP_ENV"
export DATAPIPELINES_POSTURE="$DP_POSTURE"
export DATAPIPELINES_DEMO="$DEMO_FAMILIES"
export SAMPLE_NYC_ON=$([[ $DEMO_NYC == 1 ]] && echo 1 || echo "")
export SAMPLE_TRADE_ON=$([[ $DEMO_TRADE == 1 ]] && echo 1 || echo "")

# Read KEY=value from a dotenv file; empty when absent. The trailing `|| true`
# is load-bearing under `set -euo pipefail`: grep exits 1 on a key absent from
# an EXISTING file, and a bare `x=$(env_get …)` assignment then killed the whole
# script with no message — only on machines where the key was missing, i.e.
# exactly the clean-machine path (caught by the 2026-08-29 release rehearsal).
env_get() { # file key
  [[ -f $1 ]] || return 0
  grep -E "^$2=" "$1" | head -1 | cut -d= -f2- || true
}

# The EFFECTIVE value of a key across the env-file list, in compose's own precedence
# (later file wins), with the process environment winning over all of them — which is
# what compose does. ONE derivation, so what this script PRINTS is what the container
# GETS (T137: the scaffold generated a password, printed it, and a different value
# reached the app; two lanes and the owner could not log in).
effective() { # key
  local key="$1" value=""
  local f v
  for f in "$POSTURE_ENV" "$DEMO_ENV" "$SECRETS_ENV"; do
    # demo.env only participates when this invocation passes it to compose.
    if [[ $f == "$DEMO_ENV" ]] && ((!DEMO_NYC && !DEMO_TRADE)); then continue; fi
    v=$(env_get "$f" "$key")
    [[ -n $v ]] && value="$v"
  done
  printf '%s' "${!key:-$value}"
}

# ---------------------------------------------------------------- secrets
scaffold_secrets_env() {
  [[ -f $SECRETS_ENV ]] && return 0
  echo "==> $SECRETS_ENV missing — scaffolding it with generated secrets"
  # §7 requires at least ONE authentication method, and an unset GOOGLE_* pair means
  # the stock provider is ignored — so with no OIDC creds the app refuses to start.
  # Scaffold local accounts, or `./app.sh --start` on a clean machine never becomes
  # healthy (found by adversarial verification of 026, post-merge).
  #
  # The password is GENERATED, never a shipped constant: a fixed default in a tracked
  # file is a published admin credential on an app that binds all interfaces. It is
  # written ONCE, HERE, and read back out of this file by everything that prints it.
  local pw
  pw=$(openssl rand -base64 18)
  cat >"$SECRETS_ENV" <<EOF
# Generated by app.sh $(date +%F). GIT-IGNORED — see deploy/env/secrets.env.example
# for the field reference, and docs/environments.md for what belongs in this file.
DATAPIPELINES_JWT_SECRET=$(openssl rand -base64 32)
DATAPIPELINES_DB_ENCRYPTION_KEY=$(openssl rand -base64 32)
SPRING_DATASOURCE_PASSWORD=$(openssl rand -base64 24)
DATAPIPELINES_REDIS_PASSWORD=$(openssl rand -base64 24)
DATAPIPELINES_AUTH_BASE_URL=${APP_URL}
# Empty = the stock google provider is ignored (configuration.md §7): startup then
# needs local accounts, enabled below.
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
DATAPIPELINES_AUTH_ALLOWLIST_DOMAINS=
# The zero-setup login (auth.md §5A). This one-time password is seeded onto the
# bootstrap admin at FIRST BOOT ONLY and the app forces a change at first sign-in.
# Want YOUR address to be the admin? Set it below BEFORE the first start — the seed
# fires once, at row creation, and a later change does not re-seed it.
DATAPIPELINES_AUTH_LOCAL_ENABLED=true
DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD=$pw
DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL=admin@local.test
# The demo's SELECT-only sample logins. Hex, NOT base64: hex can never contain the
# one string the loader refuses in a Postgres password — its dollar-quote tag (045
# §A) — and stays shell/SQL-quiet everywhere.
SAMPLE_PG_PASSWORD=$(openssl rand -hex 24)
SAMPLE_MYSQL_PASSWORD=$(openssl rand -hex 24)
SAMPLE_MYSQL_ROOT_PASSWORD=$(openssl rand -hex 24)
EOF
  chmod 600 "$SECRETS_ENV"
  echo "==> wrote $SECRETS_ENV — review it; set GOOGLE_* for OIDC login, or keep local accounts"
}

# ---------------------------------------------------------------- build
build() {
  local gradle_args=(:modules:app:bootJar)
  # -Pmysql adds MySQL Connector/J (GPL + FOSS exception; datasources.md §10.2),
  # which the default build deliberately omits. Either demo family's MySQL
  # datasources (weather / Comtrade) fail registration without it with
  # datasource.driver_not_loaded — at STARTUP, which under the bootstrap files
  # is a fail-fast boot.
  local flag_note=""
  if ((DEMO_NYC || DEMO_TRADE)); then gradle_args=(-Pmysql "${gradle_args[@]}"); flag_note=" [-Pmysql]"; fi
  echo "==> building jar with the pinned Gradle wrapper in $BUILDER_IMAGE (cache: .gradle-docker/)${flag_note}"
  mkdir -p "$GRADLE_CACHE"
  docker run --rm \
    -v "$PWD":/ws -w /ws \
    -u "$(id -u)":"$(id -g)" \
    -e HOME=/tmp -e GRADLE_USER_HOME=/ws/.gradle-docker \
    "$BUILDER_IMAGE" ./gradlew "${gradle_args[@]}"
  echo "==> building image $IMAGE_TAG"
  docker build -t "$IMAGE_TAG" .
}

# Whether the app container is still RUNNING (per compose ps) — the discriminator for
# a `up --wait` timeout. T75: the image HEALTHCHECK gives the app 40s start period + 3
# 30s retries to answer /ready, but a cold JVM start can outrun that on a loaded machine
# (243s measured in the 2026-09-02 rehearsal) — `up --wait` then exits 1 while the app
# is merely still booting. A container that is running is a STARTING app; anything else
# (exited, restarting, absent) is a real failure.
app_container_running() {
  "${COMPOSE[@]}" ps --format json datapipelines 2>/dev/null | python3 -c '
import json, sys
raw = sys.stdin.read()
try:
    rows = json.loads(raw)
    rows = [rows] if isinstance(rows, dict) else rows
except json.JSONDecodeError:
    rows = [json.loads(line) for line in raw.splitlines() if line.strip()]
sys.exit(0 if any(r.get("State") == "running" for r in rows) else 1)
'
}

# ---------------------------------------------------------------- the login that EXISTS
# §G / T137. The local admin credential is seeded ONCE, at the first boot that finds no
# row for the configured address, and `LocalAdminSeeder` is idempotent BY DESIGN: a
# later change to DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL is carried by the container
# and never reaches the database. Printing the FILE's values is therefore printing a
# login that may not exist — which is exactly what happened on the owner's stack
# (env said one address, `users` held demo-admin@demo.local seeded five days earlier).
#
# So ask the DATABASE, and print what it says. Empty output = no seeded admin (or no
# psql yet), and the caller falls back to describing the file honestly.
seeded_admin() { # -> "<email>|<must_change_password>"  (empty when none)
  "${COMPOSE[@]}" exec -T postgres \
    psql -qtAX -U datapipelines -d datapipelines \
    -c "SELECT email || '|' || must_change_password FROM users WHERE provider = 'bootstrap' ORDER BY created_at LIMIT 1" \
    2>/dev/null | tr -d '[:space:]' || true
}

print_login() {
  local configured password row seeded must_change
  configured=$(effective DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL)
  password=$(effective DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD)
  [[ -n $password ]] || password="<the password behind DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD_HASH>"
  row=$(seeded_admin)
  seeded="${row%%|*}"
  must_change="${row##*|}"

  if [[ -z $row ]]; then
    echo "==> no local admin has been seeded yet (the app seeds one at its first boot)."
    return 0
  fi
  if [[ $seeded != "$configured" ]]; then
    cat <<EOM
==> LOGIN: ${seeded}
    DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL says '${configured}', but this database
    was seeded with '${seeded}' and the seed fires ONCE, at row creation (auth.md
    §5A.2) — changing the variable does not re-seed, so '${configured}' has no
    account. Sign in as '${seeded}', then add or reset the other from Admin -> Users.
    A one-time password is only known from the boot that created it; reset it from
    Admin -> Users, or start from an empty database (\`${COMPOSE[*]} down -v\`).
EOM
    return 0
  fi
  if [[ $must_change == t || $must_change == true ]]; then
    cat <<EOM
==> LOGIN: ${seeded} / ${password}
    The one-time credential from ${SECRETS_ENV} is still pending — the app will make
    you set a new password at first sign-in (auth.md §5A.4).
EOM
  else
    cat <<EOM
==> LOGIN: ${seeded}
    Its password has already been changed, so the seed value in ${SECRETS_ENV} no
    longer works. Reset it from Admin -> Users if you have lost it.
EOM
  fi
}

# ---------------------------------------------------------------- verbs
start() {
  local do_build=1
  [[ ${1:-} == --no-build ]] && do_build=0
  scaffold_secrets_env
  if ((do_build)); then
    build
  else
    docker image inspect "$IMAGE_TAG" >/dev/null 2>&1 \
      || die "image $IMAGE_TAG not found — run --start without --no-build first"
    if ((DEMO_NYC || DEMO_TRADE)); then
      echo "==> NOTE: --no-build reuses $IMAGE_TAG as built. If it was built without"
      echo "    -Pmysql, the demo's MySQL datasources fail registration at startup."
    fi
  fi
  echo "==> starting env=$DP_ENV posture=$DP_POSTURE demo=${DEMO_FAMILIES:-(none)} project=$COMPOSE_PROJECT port=$APP_HOST_PORT"
  echo "==> (app healthcheck probes /ready; first boot runs migrations)"
  if ! "${COMPOSE[@]}" up -d --wait; then
    # T75: a wait that is always long enough does not exist. When the only failure is
    # the health probe timing out on a still-booting JVM, say so and exit 0 — a new
    # user on a slow laptop must not be told a working app failed. ./app.sh --status
    # flips to UP when Tomcat answers. Only a container that is NOT running keeps the
    # hard failure, with the logs to read.
    if app_container_running; then
      echo "==> stack is still starting (a cold first boot can take minutes) — check ./app.sh --status"
      exit 0
    fi
    echo "---- app container, last 40 log lines ----"
    "${COMPOSE[@]}" logs --tail 40 datapipelines || true
    die "stack did not become healthy — full logs: ./app.sh --logs"
  fi
  curl -sf "$HEALTH_URL" >/dev/null 2>&1 \
    || die "stack is up but $HEALTH_URL is not answering"
  echo "==> UP — ${APP_URL}"
  print_login
  if ((DEMO_NYC || DEMO_TRADE)); then
    cat <<EOM
==> demo data loaded (${DEMO_FAMILIES}). Your personal workspace is provisioned with
    the example pipelines. To point an agent at it: log in -> mint an API key in the
    UI -> give the agent ${APP_URL}/mcp with that key. See docs/deployment.md
    Appendix B.
EOM
  fi
}

stop() {
  echo "==> stopping the stack (data volumes kept; '${COMPOSE[*]} down -v' resets them)"
  "${COMPOSE[@]}" stop
  ((DEMO_NYC || DEMO_TRADE)) && return 0 # --stop --demo … already sees the demo services
  # 045 §C.1 (023 review): the invocation above has no demo profile, so demo
  # containers are invisible to it and a plain --stop used to leave this project's
  # demo MySQL running — verified live on a scratch stack (2026-09-02): `stop`
  # returned 0 with mysql still Up. Stop the demo services too, when they run.
  # Detection is by compose LABEL, not by the compose model: loading the model needs
  # the demo env file, which a machine that never ran --demo must not be forced to have.
  local svc running=""
  for svc in mysql sample-data sample-data-mysql sample-data-trade sample-data-trade-mysql; do
    if [ -n "$(docker ps -q \
        --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" \
        --filter "label=com.docker.compose.service=$svc" 2>/dev/null)" ]; then
      running=$svc
      break
    fi
  done
  if [[ -n $running ]]; then
    docker compose -p "$COMPOSE_PROJECT" \
      -f deploy/compose.yml -f deploy/compose.local-build.yml \
      --env-file "$POSTURE_ENV" --env-file "$DEMO_ENV" --env-file "$SECRETS_ENV" \
      --profile demo-nyc --profile demo-trade stop
    echo "==> demo services stopped too (a plain --stop covers the demo profiles)"
  fi
}

status() {
  "${COMPOSE[@]}" ps
  echo "env=$DP_ENV posture=$DP_POSTURE demo=${DEMO_FAMILIES:-(none)} project=$COMPOSE_PROJECT"
  if curl -sf "$HEALTH_URL" >/dev/null 2>&1; then
    echo "health: UP ($HEALTH_URL)"
  else
    echo "health: NOT RESPONDING ($HEALTH_URL)"
  fi
}

case ${1:-} in
  --start)
    shift
    start "$@"
    ;;
  --stop) stop ;;
  --status) status ;;
  --logs) exec "${COMPOSE[@]}" logs -f datapipelines ;;
  *)
    grep '^#   ' "$0" | sed 's/^#   //'
    exit 2
    ;;
esac
