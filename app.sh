#!/usr/bin/env bash
# Local runner — the deployment.md Appendix A stack, entirely in Docker.
# No local Java or Gradle: the jar is built by the repo's pinned Gradle wrapper
# inside a throwaway JDK container, packaged with the repo Dockerfile, and run
# via deploy/docker-compose.yml + deploy/docker-compose.local.yml — the same
# stack an engineer evaluating the project runs.
#
#   ./app.sh --start [--no-build]   build image in Docker + start the full stack
#   ./app.sh --start --demo         ...plus the published sample databases
#   ./app.sh --stop [--demo]        stop the stack, demo profile included
#   ./app.sh --status [--demo]      show services + app health
#   ./app.sh --logs                 follow the app container's logs
#
# Secrets live in deploy/.env (git-ignored). If it is missing, --start scaffolds
# it: values are carried over from .env.local when present (JWT/encryption/OIDC),
# infra passwords are generated. The Gradle cache persists in ./.gradle-docker
# (git-ignored), so only the first build is cold.
#
# --demo activates the compose `demo` profile: a MySQL service, the two one-shot
# loaders that download and checksum-verify the published sample artifacts, and
# the bootstrap/workspace settings of the demo posture. It also builds the jar
# with -Pmysql, because MySQL Connector/J is NOT in the default build (GPL +
# FOSS exception, datasources.md §10.2) and the sample-weather datasource would
# otherwise fail registration with datasource.driver_not_loaded. Missing SAMPLE_*
# keys are scaffolded into deploy/.env.demo on first use, with SAMPLE_BASE_URL
# defaulting to the published bucket (deployment.md Appendix B) — zero edits
# needed for the standard demo.

set -euo pipefail
cd "$(cd "$(dirname "$0")" && pwd)"

DEPLOY_ENV="deploy/.env"
LOCAL_ENV=".env.local"
# APP_COMPOSE_PROJECT overrides the compose project (default: the files' pinned
# "deploy") — for running a second isolated copy on one machine (CI, rehearsals).
COMPOSE=(docker compose ${APP_COMPOSE_PROJECT:+-p "$APP_COMPOSE_PROJECT"} -f deploy/docker-compose.yml -f deploy/docker-compose.local.yml)

# --demo is a MODE, not a subcommand: it changes the compose invocation for every
# verb, so it is stripped from the argument list here rather than inside start().
# Without it the demo services are invisible to compose: --status cannot show
# them, and --stop would leave the demo MySQL running — stop() compensates for
# that explicitly (045 §C.1), status does not.
DEMO=0
ARGS=()
for arg in "$@"; do
  if [[ $arg == --demo ]]; then DEMO=1; else ARGS+=("$arg"); fi
done
set -- "${ARGS[@]:-}"
# Demo keys live in their OWN env file, passed only on demo invocations: appending
# them to deploy/.env poisoned every later plain --start (compose interpolates env
# profile-independently, so the non-demo stack inherited the demo bootstrap file
# and posture — 023 review F1). Order matters: deploy/.env comes LAST so values an
# operator set there override the scaffolded demo file.
DEMO_ENV="deploy/.env.demo"
if ((DEMO)); then
  touch "$DEMO_ENV" # --stop/--status --demo may run before any --start --demo scaffolded it
  COMPOSE+=(--env-file "$DEMO_ENV" --env-file "$DEPLOY_ENV" --profile demo)
fi
# The image tag follows the compose project (default "deploy" — the files' pinned
# name): a hardcoded single tag meant every lane's --start rebuilt the tag every
# OTHER lane's stack resolves (034 F2 — 031's build overwrote 029's mid-round).
# The default lane keeps the documented tag; a second isolated copy gets its own.
# IMAGE_TAG in the environment overrides the derivation entirely.
if [[ -z ${IMAGE_TAG:-} ]]; then
  if [[ ${APP_COMPOSE_PROJECT:-deploy} == deploy ]]; then
    IMAGE_TAG="datapipelines:local"
  else
    IMAGE_TAG="datapipelines:local-${APP_COMPOSE_PROJECT}"
  fi
fi
export IMAGE_TAG
BUILDER_IMAGE="eclipse-temurin:21-jdk"
GRADLE_CACHE="$PWD/.gradle-docker"
HEALTH_URL="http://localhost:8080/health"

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

scaffold_deploy_env() {
  [[ -f $DEPLOY_ENV ]] && return 0
  echo "==> $DEPLOY_ENV missing — scaffolding it (secrets carried from $LOCAL_ENV where present)"
  local jwt enc base gid gsec admin domains local_auth local_pw
  jwt=$(env_get "$LOCAL_ENV" DATAPIPELINES_JWT_SECRET)
  enc=$(env_get "$LOCAL_ENV" DATAPIPELINES_DB_ENCRYPTION_KEY)
  base=$(env_get "$LOCAL_ENV" DATAPIPELINES_AUTH_BASE_URL)
  gid=$(env_get "$LOCAL_ENV" GOOGLE_CLIENT_ID)
  gsec=$(env_get "$LOCAL_ENV" GOOGLE_CLIENT_SECRET)
  admin=$(env_get "$LOCAL_ENV" DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL)
  domains=$(env_get "$LOCAL_ENV" DATAPIPELINES_AUTH_ALLOWLIST_DOMAINS)

  # §7 requires at least ONE authentication method, and empty GOOGLE_* below means the
  # stock provider is ignored — so with no OIDC creds the app refuses to start. Scaffold
  # local accounts in that case, or `./app.sh --start` on a clean machine never becomes
  # healthy (found by adversarial verification of 026, post-merge).
  #
  # The password is GENERATED, never a shipped constant: a fixed default in a committed
  # template is a published admin credential on an app that binds all interfaces.
  local_auth=""
  if [[ -z ${gid:-} || -z ${gsec:-} ]]; then
    local_pw=$(openssl rand -base64 18)
    local_auth=$'DATAPIPELINES_AUTH_LOCAL_ENABLED=true\nDATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD='"$local_pw"
  fi
  cat >"$DEPLOY_ENV" <<EOF
# Generated by app.sh $(date +%F) from .env.local + fresh infra passwords.
# Git-ignored. See deploy/.env.example for the field reference.
METADATA_DB_PASSWORD=$(openssl rand -base64 24)
REDIS_PASSWORD=$(openssl rand -base64 24)
JWT_SECRET=${jwt:-$(openssl rand -base64 32)}
ENCRYPTION_KEY=${enc:-$(openssl rand -base64 32)}
DATAPIPELINES_AUTH_BASE_URL=${base:-http://localhost:8080}
# Empty = the stock google provider is ignored (configuration.md §7): startup then
# needs local accounts enabled (deploy/.env.example, auth.md §5A) or real creds here.
GOOGLE_CLIENT_ID=${gid:-}
GOOGLE_CLIENT_SECRET=${gsec:-}
# This email becomes admin at its FIRST login (auth.md §4.4). Deliberately NOT
# written as an empty assignment when unknown: a later env file could never
# override an empty value here (compose precedence), and the demo's placeholder
# actor needs to win when you haven't set one. Add the line yourself to claim admin:
# DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL=you@yourdomain.com
${admin:+DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL=$admin}
DATAPIPELINES_AUTH_ALLOWLIST_DOMAINS=${domains:-}
${local_auth}
EOF
  # Strip the blank line the empty substitution leaves behind.
  sed -i.bak '/^$/d' "$DEPLOY_ENV" && rm -f "$DEPLOY_ENV.bak"
  echo "==> wrote $DEPLOY_ENV — review it; set Google creds for OIDC login, or enable local accounts (see deploy/.env.example)"
  if [[ -n $local_auth ]]; then
    echo "==> no Google creds found, so LOCAL ACCOUNTS were enabled so the app can start."
    echo "    Sign in at http://localhost:8080/login as the bootstrap admin with:"
    echo "      password: $local_pw"
    echo "    It is stored in $DEPLOY_ENV and you must change it at first login."
  fi
}

# Scaffold missing demo keys into deploy/.env.demo — NEVER into deploy/.env: keys
# there are interpolated on every invocation, demo or not, and appending them
# poisoned every later plain --start (023 review F1). Key-by-key and append-only;
# a value the operator set in EITHER file is respected (deploy/.env wins — it is
# passed last). SAMPLE_BASE_URL defaults to the PUBLISHED bucket (deployment.md
# Appendix B; licence gate stamped 2026-08-29) — override it in deploy/.env for
# a mirror or a locally built artifact set.
ensure_demo_env() {
  local added=0
  add_key() { # key value
    grep -qE "^$1=" "$DEPLOY_ENV" && return 0
    grep -qE "^$1=" "$DEMO_ENV" && return 0
    printf '%s=%s\n' "$1" "$2" >> "$DEMO_ENV"
    added=1
  }
  add_key SAMPLE_BASE_URL "https://datapipelines-co.s3.amazonaws.com/sample-data/mobility"
  add_key SAMPLE_VERSION "v1"
  add_key SAMPLE_DB_USER "dp_demo_ro"
  # hex, NOT base64: hex can never contain the one string the loader refuses
  # in a Postgres password — its dollar-quote tag (045 §A) — and stays
  # shell/SQL-quiet everywhere. (Before 045 the loader's charset allowlist
  # rejected base64's +/ outright; any charset works now, hex is kept.)
  add_key SAMPLE_PG_PASSWORD "$(openssl rand -hex 24)"
  add_key SAMPLE_MYSQL_PASSWORD "$(openssl rand -hex 24)"
  add_key SAMPLE_MYSQL_ROOT_PASSWORD "$(openssl rand -hex 24)"
  add_key DATAPIPELINES_BOOTSTRAP_DATASOURCES_FILE "/srv/sample/bootstrap-datasources.yml"
  add_key DATAPIPELINES_BOOTSTRAP_EXAMPLES_FILE "/srv/sample/examples.json"
  add_key DATAPIPELINES_WORKSPACES_PROVISIONING_MODE "auto-per-user"
  add_key DATAPIPELINES_WORKSPACES_MEMBER_DATASOURCES_ENABLED "false"
  # Zero-setup login (auth.md §5A): the demo needs NO OIDC client at all. Local
  # accounts are enabled and the FIRST ADMIN gets a documented one-time password —
  # the app forces a change at first login (§5A.4), so the fixed value is a demo
  # convenience, not a standing credential. The admin email key below names the
  # account this seed lands on (§3.4 cross-key rule).
  add_key DATAPIPELINES_AUTH_LOCAL_ENABLED "true"
  add_key DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD "demo-admin"
  # The bootstrap ACTOR (auth.md §4.4/§6.1): registration needs a users row for
  # datasources.created_by, and startup fail-fasts without one (the §3.18
  # cross-key rule) — which broke the zero-edit demo on a machine with no
  # .env.local (caught by the 2026-08-29 release rehearsal). The placeholder
  # actor below keeps zero-edit true; its OIDC login never happens, so §4.2
  # linking never fires. Want YOUR login to be admin? Set
  # DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL to your email in deploy/.env
  # BEFORE the first start (the grant fires only at row creation).
  # Non-empty-aware on BOTH files: an empty `KEY=` line in deploy/.env would make
  # add_key skip this AND clobber the demo file's value via env-file precedence
  # (rehearsal catch #3). If the effective value is still empty after this, die
  # with words rather than letting the app's §3.18 fail-fast print a stack trace.
  if [[ -z $(env_get "$DEPLOY_ENV" DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL) &&
        -z $(env_get "$DEMO_ENV" DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL) ]]; then
    printf '%s=%s\n' DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL "demo-admin@demo.local" >> "$DEMO_ENV"
    added=1
  fi
  if grep -qE '^DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL=$' "$DEPLOY_ENV"; then
    die "deploy/.env has an EMPTY DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL= line.
  The demo needs a bootstrap actor (auth.md §4.4). Set your email there — or delete
  the line entirely and the demo's placeholder actor takes over."
  fi
  if ((added)); then echo "==> appended the missing SAMPLE_*/demo keys to $DEMO_ENV"; fi
  # The EFFECTIVE value: deploy/.env wins over the scaffolded demo file.
  local base
  base=$(env_get "$DEPLOY_ENV" SAMPLE_BASE_URL)
  [[ -n $base ]] || base=$(env_get "$DEMO_ENV" SAMPLE_BASE_URL)
  if [[ $base == 'https://<bucket>'* ]]; then
    die "SAMPLE_BASE_URL is still the <bucket> placeholder (set it in $DEPLOY_ENV or $DEMO_ENV).
  The sample artifacts are published to object storage by the owner; point this
  at that bucket (deployment.md Appendix B), or at a local server for a build
  you produced yourself — the loader fetches \$BASE_URL/\$VERSION/manifest.json
  and the manifest declares v1, so serve the artifacts AS a v1 directory:
    (cd scripts/sample-data/work && ln -sfn artifacts v1 && python3 -m http.server 8099)
    SAMPLE_BASE_URL=http://host.docker.internal:8099  SAMPLE_VERSION=v1"
  fi
}

build() {
  local gradle_args=(:modules:app:bootJar)
  # -Pmysql adds MySQL Connector/J (GPL + FOSS exception; datasources.md §10.2),
  # which the default build deliberately omits. The demo's sample-weather
  # datasource is MYSQL, and registration fails with datasource.driver_not_loaded
  # without it — at STARTUP, which under the bootstrap file is a fail-fast boot.
  if ((DEMO)); then gradle_args=(-Pmysql "${gradle_args[@]}"); fi
  # NOTE: ((DEMO)) arithmetic, not ${DEMO:+…}: DEMO is always set ("0" or "1"), so
  # the :+ form claimed [-Pmysql] on every build, demo or not (023 review F10).
  local flag_note=""
  ((DEMO)) && flag_note=" [-Pmysql]"
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

start() {
  local do_build=1
  [[ ${1:-} == --no-build ]] && do_build=0
  scaffold_deploy_env
  if ((DEMO)); then ensure_demo_env; fi
  if ((do_build)); then
    build
  else
    docker image inspect "$IMAGE_TAG" >/dev/null 2>&1 \
      || die "image $IMAGE_TAG not found — run --start without --no-build first"
    if ((DEMO)); then
      echo "==> NOTE: --no-build reuses $IMAGE_TAG as built. If it was built without"
      echo "    -Pmysql, the sample-weather datasource fails registration at startup."
    fi
  fi
  echo "==> starting the stack (app healthcheck probes /ready; first boot runs migrations)"
  if ! "${COMPOSE[@]}" up -d --wait; then
    echo "---- app container, last 40 log lines ----"
    "${COMPOSE[@]}" logs --tail 40 datapipelines || true
    die "stack did not become healthy — full logs: ./app.sh --logs"
  fi
  curl -sf "$HEALTH_URL" >/dev/null 2>&1 \
    || die "stack is up but $HEALTH_URL is not answering"
  echo "==> UP — http://localhost:8080"
  if ((DEMO)); then
    cat <<'EOM'
==> demo data loaded. Log in at http://localhost:8080 with the LOCAL account
    demo-admin@demo.local / demo-admin — no OIDC client needed; you'll be asked
    to set a new password on first sign-in (auth.md §5A). (An OIDC provider from
    deploy/.env works too, if you configured one.) Your personal workspace is
    provisioned with the example pipelines. To point an agent at it: log in ->
    mint an API key in the UI -> give the agent http://localhost:8080/mcp with
    that key. See docs/deployment.md Appendix B.
EOM
  fi
}

stop() {
  echo "==> stopping the stack (data volumes kept; '${COMPOSE[*]} down -v' resets them)"
  "${COMPOSE[@]}" stop
  ((DEMO)) && return 0 # --stop --demo already sees the demo services
  # 045 §C.1 (023 review): the invocation above has no demo profile, so demo
  # containers are invisible to it and a plain --stop used to leave this
  # project's demo MySQL running — verified live on a scratch stack
  # (2026-09-02): `stop` returned 0 with mysql still Up. Stop the demo services
  # too, when they run. Detection is by compose LABEL, not by the compose
  # model: loading the model needs the demo env files, which a machine that
  # never ran --demo must not be forced to have.
  local proj svc running=""
  proj=${APP_COMPOSE_PROJECT:-deploy}
  for svc in mysql sample-data sample-data-mysql; do
    if [ -n "$(docker ps -q \
        --filter "label=com.docker.compose.project=$proj" \
        --filter "label=com.docker.compose.service=$svc" 2>/dev/null)" ]; then
      running=$svc
      break
    fi
  done
  if [[ -n $running ]]; then
    touch "$DEMO_ENV" # exists whenever --start --demo ever ran; belt for a manual `up`
    local -a env_args=(--env-file "$DEMO_ENV")
    # deploy/.env exists whenever app.sh itself started the stack (start()
    # scaffolds it); a manually `compose up`-ed scratch stack may lack it, and
    # refusing to stop running containers over a missing auxiliary file would
    # recreate this finding. Required vars then come from the process env.
    if [[ -f $DEPLOY_ENV ]]; then env_args+=(--env-file "$DEPLOY_ENV"); fi
    docker compose ${APP_COMPOSE_PROJECT:+-p "$APP_COMPOSE_PROJECT"} \
      -f deploy/docker-compose.yml -f deploy/docker-compose.local.yml \
      "${env_args[@]}" --profile demo stop
    echo "==> demo services stopped too (a plain --stop covers the demo profile)"
  fi
}

status() {
  "${COMPOSE[@]}" ps
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
