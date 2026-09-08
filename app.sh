#!/usr/bin/env bash
# Local runner — the deployment.md Appendix A stack, entirely in Docker.
# No local Java or Gradle: the jar is built by the repo's pinned Gradle wrapper
# inside a throwaway JDK container, packaged with the repo Dockerfile, and run
# via deploy/compose.yml + deploy/compose.local-build.yml — the same stack an
# engineer evaluating the project runs.
#
#   ./app.sh --scaffold                   write deploy/secrets.env and stop
#   ./app.sh --start [--env <label>] [--posture development|hardened]
#            [--demo nyc,trade] [--no-build]
#   ./app.sh --stop [--demo <families>]   stop the stack, demo services included
#   ./app.sh --clean [--yes]              CLEAN SLATE: stop everything and delete this
#                                         project's METADATA volume (pipelines, templates,
#                                         executions, users, keys, workspaces, datasource
#                                         rows) — demo source data and downloaded artifacts
#                                         are kept; the next --start re-seeds the demo
#   ./app.sh --status [--demo <families>] show services + app health + the login
#   ./app.sh --logs                       follow the app container's logs
#
# THE TWO VARIABLES (docs/environments.md). --env is the ORG's label for this
# deployment (default `local`); --posture is the PRODUCT's stance, `development`
# or `hardened`. Only the environment named `local` gets a posture for free —
# any other name with no posture refuses to start, naming both variables. This
# script is a LOADER: everything it does is assemble env files that work just as
# well under `java -jar`, systemd, Kubernetes, ECS and Nomad.
#
#   deploy/env/defaults.env   TRACKED, non-secret: every variable the app reads, with
#                             the value this deployment defaults to. One file (081).
#   deploy/secrets.env        GIT-IGNORED: every secret, plus every override that
#                             belongs to THIS deployment. Scaffolded here from
#                             deploy/secrets.env.example.
#
# ...passed in that order, secrets LAST, so a value an operator sets in
# deploy/secrets.env always wins. Two files, in that order, under every loader.
#
# --posture and --env are EXPLICIT OVERRIDES: they are exported into this process, and
# a shell variable beats both files. Leaving them off uses defaults.env's values.
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
SECRETS_TEMPLATE="deploy/secrets.env.example"
DEFAULTS_ENV="deploy/env/defaults.env"

die() { echo "app.sh: $*" >&2; exit 1; }

# Read KEY=value from a dotenv file; empty when absent. The trailing `|| true`
# is load-bearing under `set -euo pipefail`: grep exits 1 on a key absent from
# an EXISTING file, and a bare `x=$(env_get …)` assignment then killed the whole
# script with no message — only on machines where the key was missing, i.e.
# exactly the clean-machine path (caught by the 2026-08-29 release rehearsal).
env_get() { # file key
  [[ -f $1 ]] || return 0
  grep -E "^$2=" "$1" | head -1 | cut -d= -f2- || true
}

# What the two env files say a key is, later file winning — the same precedence every
# loader applies. NOT the process environment: the callers below layer that on top
# themselves, because a command-line flag has to beat both.
from_files() { # key
  local v value=""
  for f in "$DEFAULTS_ENV" "$SECRETS_ENV"; do
    v=$(env_get "$f" "$1")
    [[ -n $v ]] && value="$v"
  done
  printf '%s' "$value"
}

# ---------------------------------------------------------------- arguments
# --env / --posture / --demo are MODES, not subcommands: they change the compose
# invocation for every verb, so they are stripped from the argument list here
# rather than inside start(). Without the demo families the demo services are
# invisible to compose: --status cannot show them, and --stop would leave this
# project's demo MySQL running — stop() compensates for that explicitly (045 §C.1).
# The three values, in the precedence a loader has to honour: the flag, then this
# shell, then the env files (secrets.env over defaults.env). 081: reading only the
# shell made this script blind to its own contract — `DATAPIPELINES_POSTURE=hardened`
# in deploy/secrets.env with `--demo nyc` on the command line got a `development`
# refusal check, started containers, and was refused by the APP a minute later. The
# refusal has to arrive before a container does, which means app.sh must resolve the
# posture the same way the app will.
DP_ENV="${DATAPIPELINES_ENV:-$(from_files DATAPIPELINES_ENV)}"
DP_ENV="${DP_ENV:-local}"
DP_POSTURE="${DATAPIPELINES_POSTURE:-$(from_files DATAPIPELINES_POSTURE)}"
DP_DEMO="${DATAPIPELINES_DEMO:-$(from_files DATAPIPELINES_DEMO)}"
POSTURE_FROM_FLAG=0
CLEAN_CONFIRMED=0
ARGS=()
while (($#)); do
  case "$1" in
    --env) shift; [[ ${1:-} ]] || die "--env needs a value (your org's name for this deployment)"; DP_ENV="$1" ;;
    --posture) shift; [[ ${1:-} ]] || die "--posture needs a value: development or hardened"; DP_POSTURE="$1"; POSTURE_FROM_FLAG=1 ;;
    --demo) shift; [[ ${1:-} ]] || die "--demo needs a family list, e.g. --demo nyc or --demo nyc,trade,lake"; DP_DEMO="$1" ;;
    --yes) CLEAN_CONFIRMED=1 ;;
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
[[ -f $DEFAULTS_ENV ]] || die "$DEFAULTS_ENV is missing — it is the tracked settings file every
  loader reads first. Restore it from the repository (git checkout -- $DEFAULTS_ENV)."

DEMO_FAMILIES=$(printf '%s' "$DP_DEMO" | tr -d '[:space:]')
DEMO_NYC=0
DEMO_TRADE=0
DEMO_LAKE=0
if [[ -n $DEMO_FAMILIES ]]; then
  IFS=',' read -r -a _families <<<"$DEMO_FAMILIES"
  for f in "${_families[@]}"; do
    case "$f" in
      nyc) DEMO_NYC=1 ;;
      trade) DEMO_TRADE=1 ;;
      # 089 §E: the lake family has NO loader — nothing downloads; the app reads
      # the published objects in place. The token only flips the profile and the
      # bootstrap-list markers on.
      lake) DEMO_LAKE=1 ;;
      "") ;;
      *) die "--demo: unknown sample-data family '$f'. The families are nyc, trade and lake." ;;
    esac
  done
  if [[ $DP_POSTURE == hardened ]]; then
    # Say WHERE the posture came from. Reading it out of deploy/secrets.env is the whole
    # point of resolving it from the files, and "run it under --posture development" is
    # useless advice to someone who never typed a posture.
    posture_source="the default for DATAPIPELINES_ENV=local"
    [[ -n $(env_get "$DEFAULTS_ENV" DATAPIPELINES_POSTURE) ]] && posture_source="$DEFAULTS_ENV"
    [[ -n $(env_get "$SECRETS_ENV" DATAPIPELINES_POSTURE) ]] && posture_source="$SECRETS_ENV"
    [[ -n ${DATAPIPELINES_POSTURE:-} ]] && posture_source="the DATAPIPELINES_POSTURE in this shell"
    ((POSTURE_FROM_FLAG)) && posture_source="--posture on the command line"
    die "--demo is refused under the hardened posture: demo registers sample
  datasources and seeds example content, which is out-of-the-box EVALUATION, not a
  hardened deployment. The posture is 'hardened' because of $posture_source.
  Run it under --posture development, or drop --demo (docs/environments.md)."
  fi
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

# The env-file list, in precedence order (compose: later files win). Every verb uses the
# same list so `--status` and `--stop` see exactly the stack `--start` created.
#
# A FUNCTION, not a one-shot assignment: deploy/secrets.env may not exist yet, and compose
# errors outright on an `--env-file` that is missing. `--stop`/`--status` on a machine that
# has never started anything must still work (they did before, via a `touch` of the demo
# file), and `--start` must pick the file up AFTER scaffolding it — so start() re-assembles.
assemble_compose() {
  COMPOSE=(docker compose -p "$COMPOSE_PROJECT"
    -f deploy/compose.yml -f deploy/compose.local-build.yml
    --env-file "$DEFAULTS_ENV")
  [[ -f $SECRETS_ENV ]] && COMPOSE+=(--env-file "$SECRETS_ENV")
  ((DEMO_NYC)) && COMPOSE+=(--profile demo-nyc)
  ((DEMO_TRADE)) && COMPOSE+=(--profile demo-trade)
  ((DEMO_LAKE)) && COMPOSE+=(--profile demo-lake)
  return 0
}
assemble_compose

# The variables the compose file interpolates that are NOT in any env file: the two
# that ARE the invocation, and the ON markers the bootstrap lists are built from.
# Exported (not written to a file) because they are this invocation's flags — a
# family switched off later must take effect without hand-editing anything.
export DATAPIPELINES_ENV="$DP_ENV"
export DATAPIPELINES_POSTURE="$DP_POSTURE"
export DATAPIPELINES_DEMO="$DEMO_FAMILIES"
export SAMPLE_NYC_ON=$([[ $DEMO_NYC == 1 ]] && echo 1 || echo "")
export SAMPLE_TRADE_ON=$([[ $DEMO_TRADE == 1 ]] && echo 1 || echo "")
export SAMPLE_LAKE_ON=$([[ $DEMO_LAKE == 1 ]] && echo 1 || echo "")

# The EFFECTIVE value of a key across the env-file list, in compose's own precedence
# (later file wins), with the process environment winning over all of them — which is
# what compose does. ONE derivation, so what this script PRINTS is what the container
# GETS (T137: the scaffold generated a password, printed it, and a different value
# reached the app; two lanes and the owner could not log in).
effective() { # key
  local key="$1" value=""
  local f v
  for f in "$DEFAULTS_ENV" "$SECRETS_ENV"; do
    v=$(env_get "$f" "$key")
    [[ -n $v ]] && value="$v"
  done
  printf '%s' "${!key:-$value}"
}

# ---------------------------------------------------------------- secrets
# The scaffold is GENERATED FROM deploy/secrets.env.example, not written out here (081).
# Two hand-maintained copies of "the secrets a deployment needs" is the drift this round
# exists to remove: the 075 template omitted five keys the scaffold wrote, so a deployer
# who followed the documented `cp` path got a file that could not run the demo. One
# derivation now — the template is the list, this function only supplies VALUES:
#
#   * every key in the template's secrets section gets a generated value;
#   * the two identity keys get this box's own answers;
#   * the commented override list is copied through untouched, so the file an operator
#     ends up with names every variable they may set.
#
# `scripts/app-sh-secrets-test.sh` diffs the two key sets on every build.
scaffold_secrets_env() {
  [[ -f $SECRETS_ENV ]] && return 0
  [[ -f $SECRETS_TEMPLATE ]] || die "$SECRETS_TEMPLATE is missing — the scaffold is generated
  from it. Restore it from the repository (git checkout -- $SECRETS_TEMPLATE)."
  echo "==> $SECRETS_ENV missing — generating it from $SECRETS_TEMPLATE"

  # §7 requires at least ONE authentication method, and an unset GOOGLE_* pair means the
  # stock provider is ignored — so with no OIDC creds the app refuses to start. The
  # scaffold therefore turns local accounts ON and seeds the first admin, or
  # `./app.sh --start` on a clean machine never becomes healthy (found by adversarial
  # verification of 026, post-merge).
  #
  # The password is GENERATED, never a shipped constant: a fixed default in a tracked
  # file is a published admin credential on an app that binds all interfaces. It is
  # written ONCE, HERE, and read back out of this file by everything that prints it (T137).
  DP_GEN_B64_32=$(openssl rand -base64 32) \
  DP_GEN_B64_32B=$(openssl rand -base64 32) \
  DP_GEN_B64_24=$(openssl rand -base64 24) \
  DP_GEN_B64_24B=$(openssl rand -base64 24) \
  DP_GEN_PW=$(openssl rand -base64 18) \
  DP_GEN_HEX_A=$(openssl rand -hex 24) \
  DP_GEN_HEX_B=$(openssl rand -hex 24) \
  DP_GEN_HEX_C=$(openssl rand -hex 24) \
  DP_APP_URL="$APP_URL" \
  python3 - "$SECRETS_TEMPLATE" "$SECRETS_ENV" <<'PY'
import os, re, sys

template, out = sys.argv[1], sys.argv[2]

# key -> the value this box gives it. Anything not listed keeps the template's value,
# which for a secret is the empty string: an unused credential stays unset rather than
# being invented (an empty promotion server key is how a deployment refuses promotion).
FILL = {
    "DATAPIPELINES_JWT_SECRET": os.environ["DP_GEN_B64_32"],
    "DATAPIPELINES_DB_ENCRYPTION_KEY": os.environ["DP_GEN_B64_32B"],
    "SPRING_DATASOURCE_PASSWORD": os.environ["DP_GEN_B64_24"],
    "DATAPIPELINES_REDIS_PASSWORD": os.environ["DP_GEN_B64_24B"],
    "DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD": os.environ["DP_GEN_PW"],
    "SAMPLE_PG_PASSWORD": os.environ["DP_GEN_HEX_A"],
    "SAMPLE_MYSQL_PASSWORD": os.environ["DP_GEN_HEX_B"],
    "SAMPLE_MYSQL_ROOT_PASSWORD": os.environ["DP_GEN_HEX_C"],
    "DATAPIPELINES_AUTH_BASE_URL": os.environ["DP_APP_URL"],
    "DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL": "admin@local.test",
}
# Commented lines this box uncomments. Local password accounts are what make a clean
# machine bootable with no OIDC client at all (auth.md §5A).
UNCOMMENT = {"DATAPIPELINES_AUTH_LOCAL_ENABLED": "true"}

lines = []
for line in open(template):
    m = re.match(r"^([A-Z][A-Z0-9_]*)=(.*)$", line)
    if m and m.group(1) in FILL:
        lines.append(f"{m.group(1)}={FILL[m.group(1)]}\n")
        continue
    c = re.match(r"^#\s*([A-Z][A-Z0-9_]*)=", line)
    if c and c.group(1) in UNCOMMENT:
        lines.append(f"{c.group(1)}={UNCOMMENT[c.group(1)]}\n")
        continue
    lines.append(line)

header = [
    "# GENERATED by ./app.sh --scaffold from deploy/secrets.env.example.\n",
    "# GIT-IGNORED. Everything below is yours to edit; the comments are the template's.\n",
    "#\n",
    "# EDIT DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL BEFORE THE FIRST START if you want\n",
    "# your own address to be the administrator: the seed fires once, at row creation,\n",
    "# and changing it afterwards does not re-seed (auth.md §5A.2).\n",
    "\n",
]
open(out, "w").writelines(header + lines)
PY
  chmod 600 "$SECRETS_ENV"
  echo "==> wrote $SECRETS_ENV (mode 600) — every secret generated, every other variable"
  echo "    named in it, commented, with the default $DEFAULTS_ENV gives it."
}

# `--scaffold` exists so the admin email and the OIDC client can be set BEFORE the
# one-time seed runs. The seed fires at the first boot that finds no bootstrap row and
# never again (auth.md §5A.2), so "start it, then fix the email" does not work — the
# owner hit exactly that, and the recovery is a password reset from Admin -> Users.
scaffold_only() {
  if [[ -f $SECRETS_ENV ]]; then
    echo "==> $SECRETS_ENV already exists — nothing written (it holds live credentials)."
    echo "    Delete it yourself if you mean to start over; a stack running against the"
    echo "    old values will stop being able to read its own encrypted datasources."
    return 0
  fi
  scaffold_secrets_env
  cat <<EOM
==> Next:
    1. \$EDITOR $SECRETS_ENV
       - DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL: YOUR address. It is seeded ONCE, at
         the first start, and cannot be changed by editing this file afterwards.
       - GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET: only if you want OIDC login; local
         password accounts are already on.
       - DATAPIPELINES_POSTURE / DATAPIPELINES_ENV: uncomment to deploy as anything but
         a laptop.
    2. ./app.sh --start [--demo nyc,trade]
EOM
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

# ---------------------------------------------------------------- healthy, or a real failure
# `compose up --wait` gives up on its own schedule (the image HEALTHCHECK's 40s start
# period + 3 x 30s retries), and 075 shipped a script that treated that deadline as the
# answer: the owner's first boot printed "still starting — check ./app.sh --status" after
# about two minutes, and the stack was healthy a minute later. A first boot runs Flyway,
# seeds the admin, and — under --demo — has already restored two sample databases, so two
# minutes is simply not the length of the job.
#
# So `up --wait`'s verdict is not read at all. This loop is, and it ends on one of three
# things, none of which is a fixed nap:
#
#   healthy        /health answers 2xx        -> return, and the login gets printed
#   dead           the container is not running -> the hard failure, with logs
#   out of patience  HEALTH_WAIT_SECONDS       -> the T75 message, exit 0, NOT a failure
#
# A container that is running is a STARTING app; anything else (exited, restarting,
# absent) is a real failure and keeps the exit 1 it always had.
HEALTH_WAIT_SECONDS="${HEALTH_WAIT_SECONDS:-360}"
wait_until_healthy() {
  local waited=0 step=5
  while ((waited < HEALTH_WAIT_SECONDS)); do
    if curl -sf "$HEALTH_URL" >/dev/null 2>&1; then
      # `if`, not `((…)) && echo`: under `set -e` an AND-list whose left side is false
      # is a failed command and kills the script — here, on the fast path where the app
      # was healthy on the first probe.
      if ((waited > 0)); then echo "==> healthy after ${waited}s"; fi
      return 0
    fi
    if ! app_container_running; then
      echo "---- app container, last 40 log lines ----"
      "${COMPOSE[@]}" logs --tail 40 datapipelines || true
      die "the app container is not running — full logs: ./app.sh --logs"
    fi
    # A progress line every 15s: silence for six minutes is indistinguishable from a hang,
    # and the owner had no way to tell which one he was watching.
    if ((waited % 15 == 0)); then
      printf '    ... still booting (%ds; migrations and seeding run once)\n' "$waited"
    fi
    sleep "$step"
    waited=$((waited + step))
  done
  cat <<EOM
==> stack is STILL starting after ${HEALTH_WAIT_SECONDS}s. Nothing has failed — the
    container is running — but this box is slower than the bound. Watch it with
    ./app.sh --logs, and ./app.sh --status flips to UP the moment Tomcat answers
    (it prints the login too). Raise the bound with HEALTH_WAIT_SECONDS=900.
EOM
  exit 0
}

# ---------------------------------------------------------------- verbs
start() {
  local do_build=1
  [[ ${1:-} == --no-build ]] && do_build=0
  # Whether this start is also a first setup — asked BEFORE the scaffold runs, because
  # the scaffold is what creates the file. A brand-new deploy/secrets.env carries a
  # one-time admin password that the first boot seeds and no later boot will (auth.md
  # §5A.2), and saying so at the moment it happens is the difference between "I can edit
  # this later" and the reset-from-Admin-Users recovery the owner needed.
  local seeds_now=false
  [[ -f $SECRETS_ENV ]] || seeds_now=true
  scaffold_secrets_env
  assemble_compose # the file may have just been created; the list must carry it
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
  echo "==> (app healthcheck probes /ready; first boot runs migrations and seeds the admin)"
  if $seeds_now; then
    echo "==> FIRST START: the one-time admin credential just generated into $SECRETS_ENV"
    echo "    is seeded at this boot and at no later one. To choose the administrator's"
    echo "    address yourself, stop here and use ./app.sh --scaffold next time."
  fi
  "${COMPOSE[@]}" up -d --wait || true
  wait_until_healthy
  echo "==> UP — ${APP_URL}"
  print_login
  if ((DEMO_NYC || DEMO_TRADE || DEMO_LAKE)); then
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
  ((DEMO_NYC || DEMO_TRADE || DEMO_LAKE)) && return 0 # --stop --demo … already sees the demo services
  # 045 §C.1 (023 review): the invocation above has no demo profile, so demo
  # containers are invisible to it and a plain --stop used to leave this project's
  # demo MySQL running — verified live on a scratch stack (2026-09-02): `stop`
  # returned 0 with mysql still Up. Stop the demo services too, when they run.
  # Detection is by compose LABEL, not by the compose model: loading the model needs
  # the demo env file, which a machine that never ran --demo must not be forced to have.
  local svc running=""
  for svc in mysql sample-data sample-data-mysql sample-data-trade sample-data-trade-mysql sample-data-lake; do
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
      --env-file "$DEFAULTS_ENV" --env-file "$SECRETS_ENV" \
      --profile demo-nyc --profile demo-trade --profile demo-lake stop
    echo "==> demo services stopped too (a plain --stop covers the demo profiles)"
  fi
}

# --clean: the clean slate the owner asked for (2026-09-08, "I don't want to see any
# previous pipelines"). Authored content lives in ONE place — the metadata Postgres volume —
# beside everything else the app owns: users, API keys, workspaces, datasource rows,
# executions. Deleting that volume is the whole reset; the next --start migrates a fresh
# database, seeds the local admin from deploy/secrets.env and re-imports the demo families.
# It does NOT touch:
#   ${PROJECT}-mysql-data   — demo SOURCE data (weather, Comtrade) the loaders filled;
#   ${PROJECT}-sample-data  — the downloaded, checksum-verified artifacts (gigabytes).
# A destructive verb never runs bare (MISTAKES.md, "Destructive-by-Default CLI Verbs"): it
# prints exactly what it will delete and needs the project name typed back, or --yes when
# there is no terminal. Names are EXPLICIT — never `down -v`, whose volume set is whatever
# the interpolated model says it is (the 2026-09-04 lane incident).
clean() {
  local volume="${COMPOSE_PROJECT}-postgres-data"
  echo "==> CLEAN SLATE for compose project '$COMPOSE_PROJECT'"
  echo "    will stop and remove its containers and network, then DELETE the volume:"
  echo "      $volume   (metadata: pipelines, templates, executions, users, keys, workspaces, datasources)"
  echo "    will KEEP: ${COMPOSE_PROJECT}-mysql-data (demo source data), ${COMPOSE_PROJECT}-sample-data (downloaded artifacts)"
  if ((!CLEAN_CONFIRMED)); then
    if [[ -t 0 ]]; then
      local typed
      read -r -p "    type the project name ($COMPOSE_PROJECT) to confirm: " typed
      [[ $typed == "$COMPOSE_PROJECT" ]] || die "--clean: '$typed' is not '$COMPOSE_PROJECT'; nothing was removed"
    else
      die "--clean deletes data and no terminal is attached to confirm on; re-run with --yes"
    fi
  fi
  # Every profile, so the demo one-shot containers go too — the same all-profiles model
  # stop() uses for the same reason. `down` removes containers + network and KEEPS volumes.
  local all=(docker compose -p "$COMPOSE_PROJECT"
    -f deploy/compose.yml -f deploy/compose.local-build.yml
    --env-file "$DEFAULTS_ENV")
  [[ -f $SECRETS_ENV ]] && all+=(--env-file "$SECRETS_ENV")
  all+=(--profile demo-nyc --profile demo-trade --profile demo-lake)
  "${all[@]}" down
  if docker volume inspect "$volume" >/dev/null 2>&1; then
    docker volume rm "$volume" >/dev/null
    echo "==> deleted $volume"
  else
    echo "==> $volume did not exist (nothing to delete)"
  fi
  echo "==> clean. Next: ./app.sh --start${DEMO_FAMILIES:+ --demo $DEMO_FAMILIES} — a fresh database, the demo re-seeded."
  echo "    Sign-in users are re-created on their next login; API keys are gone and must be minted again."
}

status() {
  "${COMPOSE[@]}" ps
  echo "env=$DP_ENV posture=$DP_POSTURE demo=${DEMO_FAMILIES:-(none)} project=$COMPOSE_PROJECT"
  if curl -sf "$HEALTH_URL" >/dev/null 2>&1; then
    echo "health: UP ($HEALTH_URL)"
  else
    echo "health: NOT RESPONDING ($HEALTH_URL)"
  fi
  # The owner asked where the password is, and only --start said. It is the same
  # derivation: read the seeded row out of the database and print the login that EXISTS
  # (T137), so --status answers the question at any time, not once at boot.
  print_login
}

case ${1:-} in
  --scaffold) scaffold_only ;;
  --start)
    shift
    start "$@"
    ;;
  --stop) stop ;;
  --clean) clean ;;
  --status) status ;;
  --logs) exec "${COMPOSE[@]}" logs -f datapipelines ;;
  *)
    grep '^#   ' "$0" | sed 's/^#   //'
    exit 2
    ;;
esac
