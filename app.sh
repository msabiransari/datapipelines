#!/usr/bin/env bash
# Local dev runner — DEVELOPMENT.md §2 (infra), §4 (.env.local) and §6 (build+run)
# as one command, without an IDE and without a Gradle daemon serving the app:
# the app runs as a plain `java -jar` background process.
#
#   ./app.sh --start [--no-build]   start dev stack + app (--no-build reuses the last jar)
#   ./app.sh --stop                 stop app + dev stack (Postgres data volume kept)
#   ./app.sh --status               show stack, app process and health state
#   ./app.sh --logs                 tail the app log
#
# PID and log live in .run/ (git-ignored). Secrets come from .env.local (§4),
# which is sourced verbatim — never committed. Heap defaults to -Xms256m -Xmx1g;
# override via APP_JAVA_OPTS. Gradle is used only for the build step and its
# daemon idles out on its own — it never serves the running app.

set -euo pipefail
cd "$(cd "$(dirname "$0")" && pwd)"

ENV_FILE=".env.local"
RUN_DIR=".run"
PID_FILE="$RUN_DIR/app.pid"
LOG_FILE="$RUN_DIR/app.log"
COMPOSE=(docker compose -f deploy/docker-compose.dev.yml)
HEALTH_URL="http://localhost:8080/health"
JAVA_OPTS_DEFAULT="-Xms256m -Xmx1g"

die() { echo "app.sh: $*" >&2; exit 1; }

# Prints the app PID if that exact process is still alive; clears a stale pidfile.
app_pid() {
  [[ -f $PID_FILE ]] || return 0
  local pid
  pid=$(cat "$PID_FILE")
  if kill -0 "$pid" 2>/dev/null; then
    echo "$pid"
  else
    rm -f "$PID_FILE"
  fi
}

start() {
  local build=1
  [[ ${1:-} == --no-build ]] && build=0
  [[ -f $ENV_FILE ]] || die "$ENV_FILE not found — cp .env.example $ENV_FILE and fill it in (DEVELOPMENT.md §4)"
  local running
  running=$(app_pid)
  [[ -z $running ]] || die "already running (pid $running) — ./app.sh --stop first"

  echo "==> dev stack (postgres :5434, redis :6381)"
  "${COMPOSE[@]}" up -d --wait

  if ((build)); then
    echo "==> building jar (:modules:app:bootJar)"
    ./gradlew -q :modules:app:bootJar
  fi
  local jar
  jar=$(ls -t modules/app/build/libs/*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -1) || true
  [[ -n ${jar:-} ]] || die "no jar under modules/app/build/libs/ — run --start without --no-build"

  mkdir -p "$RUN_DIR"
  echo "==> starting $jar (dev profile; log: $LOG_FILE)"
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
  # shellcheck disable=SC2086
  nohup java ${APP_JAVA_OPTS:-$JAVA_OPTS_DEFAULT} -jar "$jar" \
    --spring.profiles.active=dev >"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"

  echo -n "==> waiting for $HEALTH_URL "
  for _ in $(seq 1 60); do
    if curl -sf "$HEALTH_URL" >/dev/null 2>&1; then
      echo
      echo "==> UP — http://localhost:8080 (pid $(cat "$PID_FILE"))"
      return 0
    fi
    if [[ -z $(app_pid) ]]; then
      echo
      echo "---- last 40 log lines ($LOG_FILE) ----"
      tail -40 "$LOG_FILE"
      die "app process died during startup"
    fi
    echo -n "."
    sleep 2
  done
  echo
  tail -40 "$LOG_FILE"
  die "no healthy response after 120s — full log: $LOG_FILE"
}

stop() {
  local pid
  pid=$(app_pid)
  if [[ -n $pid ]]; then
    echo "==> stopping app (pid $pid)"
    kill "$pid"
    for _ in $(seq 1 15); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$pid" 2>/dev/null; then
      echo "==> still alive after 15s, kill -9"
      kill -9 "$pid"
    fi
    rm -f "$PID_FILE"
  else
    echo "==> app not running"
  fi
  echo "==> stopping dev stack (data volume kept; 'docker compose -f deploy/docker-compose.dev.yml down -v' resets it — DEVELOPMENT.md §2)"
  "${COMPOSE[@]}" stop
}

status() {
  "${COMPOSE[@]}" ps
  local pid
  pid=$(app_pid)
  if [[ -n $pid ]]; then
    echo "app: running (pid $pid)"
    if curl -sf "$HEALTH_URL" >/dev/null 2>&1; then
      echo "health: UP ($HEALTH_URL)"
    else
      echo "health: NOT RESPONDING ($HEALTH_URL)"
    fi
  else
    echo "app: not running"
  fi
}

case ${1:-} in
  --start)
    shift
    start "$@"
    ;;
  --stop) stop ;;
  --status) status ;;
  --logs) exec tail -f "$LOG_FILE" ;;
  *)
    grep '^#   ' "$0" | sed 's/^#   //'
    exit 2
    ;;
esac
