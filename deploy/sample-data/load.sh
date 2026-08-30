#!/usr/bin/env sh
# deploy/sample-data/load.sh — the deployment-side sample-data loader
# (sample-data design §5). Downloads the published artifacts, verifies every
# one of them against the manifest, and restores them into this deployment's
# engines.
#
#   load.sh <base-url> <version> [--engines postgres,sqlite|mysql]
#
#   base-url   the artifact root, e.g. https://<bucket>.s3.amazonaws.com/sample-data/mobility
#   version    the immutable version directory under it, e.g. v1
#   --engines  which engines this invocation owns (default: all it can reach)
#
# The compose `demo` profile runs it twice, in two one-shot services, because no
# single pinned image carries both a Postgres and a MySQL client: `sample-data`
# runs on the pinned postgres image (postgres + sqlite + the shared download),
# and `sample-data-mysql` on the pinned mysql image. Installing the missing
# client at container start would mean an unpinned package fetch in the one
# place that must be reproducible; two pinned images cost one extra service.
#
# POSIX sh, deliberately: the postgres image's /bin/sh is busybox ash. No arrays,
# no [[ ]], no ${x^^}.
#
# ORDER OF OPERATIONS IS THE CONTRACT:
#   1. download every artifact named in the manifest;
#   2. verify EVERY checksum;
#   3. only then touch an engine.
# A corrupted download must never leave a half-loaded database behind, so
# nothing that mutates state happens before step 3 completes for all artifacts.
#
# IDEMPOTENCE: each engine gets a marker table `_sample_meta(version)`. A run
# whose marker already names this version skips that engine entirely. The marker
# is written LAST, after the restore and the demo login, so a failed engine is
# left markerless and the next run redoes it — a re-run is always safe.

set -eu
# busybox ash and bash both support this; it is what stops a failed `gunzip` in
# `gunzip -c f | mysql` from being reported as the success of `mysql`.
set -o pipefail 2>/dev/null || true

SCRIPT=load.sh
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
log()  { echo "$SCRIPT: $*" >&2; }
die()  { echo "$SCRIPT: FAIL — $*" >&2; exit 1; }
step() { echo "" >&2; echo "$SCRIPT: ==> $*" >&2; }

BASE_URL="${1:-${SAMPLE_BASE_URL:-}}"
VERSION="${2:-${SAMPLE_VERSION:-}}"
[ -n "$BASE_URL" ] || die "no artifact base URL.
  usage: load.sh <base-url> <version> [--engines ...]
  Under the compose demo profile this comes from SAMPLE_BASE_URL in deploy/.env
  (see deploy/.env.example). There is no default: the published bucket is named
  at publication time, and a built-in guess would silently load someone else data."
[ -n "$VERSION" ]  || die "no artifact version.
  usage: load.sh <base-url> <version> [--engines ...]
  Under the compose demo profile this comes from SAMPLE_VERSION in deploy/.env."
shift 2 2>/dev/null || true

ENGINES=""
while [ $# -gt 0 ]; do
  case "$1" in
    --engines) ENGINES="$2"; shift 2 ;;
    --engines=*) ENGINES="${1#--engines=}"; shift ;;
    *) die "unknown argument '$1'" ;;
  esac
done

# Where the downloaded artifacts and the app-visible files live. On the compose
# demo profile this is a named volume shared by both loader services and mounted
# read-only into the app container.
SAMPLE_DIR="${SAMPLE_DIR:-/srv/sample}"
WORK="$SAMPLE_DIR/.artifacts/$VERSION"

# The SELECT-only demo login the registered datasources use (design §5, spec-1
# D6 layer 3). CREATED HERE, never assumed to exist.
DEMO_USER="${SAMPLE_DB_USER:-dp_demo_ro}"

# Every value the SQL below interpolates (023 F6). VERSION is also constrained by
# the manifest equality check, but the guard here is what the SQL itself relies on.
require_sql_safe DEMO_USER "$DEMO_USER"
require_sql_safe VERSION "$VERSION"

wants() { case ",$ENGINES," in *",$1,"*) return 0 ;; esac; [ -z "$ENGINES" ]; }

# F8 (023): the old `wants X && command -v client` read a MISSING CLIENT BINARY as
# "engine not wanted" — a one-shot loader could exit 0 having loaded nothing. An
# engine the caller NAMED (or the default set, when nothing loadable exists at
# all) whose client is absent is now fatal; only the documented default
# ("every engine this image can reach") may skip an engine for a missing binary,
# and a run that loaded NOTHING dies at the end regardless.
require_client() { # <engine> <binary>
  command -v "$2" >/dev/null 2>&1 && return 0
  if [ -n "$ENGINES" ]; then
    die "$1 is in --engines but this image has no '$2' — the engine cannot be loaded here"
  fi
  log "  $1 skipped — no $2 in this image (default: every engine this image can reach)"
  return 1
}

LOADED_ANY=0

# F6 (023): operator env values are interpolated into superuser SQL below. An
# allowlist, not escaping: the values are operator-controlled deployment facts,
# and one conservative charset keeps both engines' quoting rules out of scope.
# Anything else dies naming the variable and the legal characters.
require_sql_safe() { # <label> <value> <charset-description>
  case "$2" in
    '') die "$1 is empty" ;;
    *[!A-Za-z0-9_.:@+-]*)
      die "$1 contains characters outside [A-Za-z0-9_.:@+-] — it is interpolated into
  superuser SQL by this loader, and the charset is the injection guard (023 F6).
  Value begins: $(printf '%s' "$2" | head -c 8)…" ;;
    *) ;;
  esac
}

# --- fetch ------------------------------------------------------------------
# curl on the mysql image, busybox wget on the postgres image. Whichever exists.
fetch() { # <url> <dest>
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --retry 3 --retry-delay 2 "$1" -o "$2.part" \
      || die "could not download $1"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$2.part" "$1" \
      || die "could not download $1"
  else
    die "neither curl nor wget is available in this image"
  fi
  mv "$2.part" "$2"
}

sha_of() { sha256sum "$1" | awk '{print $1}'; }

step "manifest $BASE_URL/$VERSION/manifest.json"
mkdir -p "$WORK"
fetch "$BASE_URL/$VERSION/manifest.json" "$WORK/manifest.json"

manifest_version=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$WORK/manifest.json" | head -1)
[ "$manifest_version" = "$VERSION" ] \
  || die "manifest at $BASE_URL/$VERSION/ declares version '$manifest_version', not '$VERSION'.
  Version directories are immutable (design §4); a mismatch means the wrong
  artifacts are published under this prefix."

# file<TAB>sha256 pairs, parsed with sed rather than a JSON tool: neither pinned
# image ships jq or python, and the manifest's artifact block is a fixed shape
# this build produces. The parse is asserted below (a zero-length list is fatal).
artifact_list() {
  # The trailing `awk` is not cosmetic. `tr -d '\n'` leaves the stream with NO
  # final newline, and sed preserves that — so the LAST artifact came out as an
  # unterminated line, `wc -l` under-counted by one, and `while read` never ran
  # its body for it. The last artifact in the manifest was therefore downloaded
  # but NEVER CHECKSUM-VERIFIED, silently. awk terminates every record with ORS.
  tr -d ' \n' < "$WORK/manifest.json" \
    | sed 's/.*"artifacts":\[//; s/\],"tables".*//' \
    | sed 's/},{/}\n{/g' \
    | sed -n 's/.*"file":"\([^"]*\)".*"sha256":"\([^"]*\)".*/\1\t\2/p' \
    | awk 'NF { print }'
}

count=$(artifact_list | wc -l | tr -d ' ')
[ "$count" -gt 0 ] || die "could not read any artifact entry out of manifest.json — refusing to guess"

# NON-VACUITY GUARD. The parse above is sed over a shape this build produces; a
# manifest whose formatting drifts could match FEWER entries and the loader would
# happily report success having verified a subset. Count the `"file":` keys
# independently and demand agreement — the two counts can only agree if every
# artifact entry was parsed.
declared=$(tr -d ' \n' < "$WORK/manifest.json" \
  | sed 's/.*"artifacts":\[//; s/\],"tables".*//' \
  | tr ',' '\n' | grep -c '"file":')
[ "$count" = "$declared" ] || die "manifest.json declares $declared artifact entries but only $count parsed.
  The loader will not verify a subset and call it verified. This is a parser/manifest
  mismatch, not a network problem."
log "manifest lists $count artifact(s)"

# --- 1 + 2: download and verify EVERYTHING before touching any engine --------
step "downloading and verifying $count artifact(s) — no engine is touched until every one passes"
artifact_list | while IFS="$(printf '\t')" read -r file want; do
  dest="$WORK/$file"
  if [ -f "$dest" ] && [ "$(sha_of "$dest")" = "$want" ]; then
    log "  $file  cached, checksum ok"
    continue
  fi
  log "  $file  downloading"
  fetch "$BASE_URL/$VERSION/$file" "$dest"
  got=$(sha_of "$dest")
  [ "$got" = "$want" ] || {
    rm -f "$dest"
    die "checksum mismatch for $file
  manifest: $want
  download: $got
  Nothing has been written to any database. Re-run to retry; if it persists,
  the published artifact and its manifest disagree and must not be loaded."
  }
  log "  $file  checksum ok"
done
# `while` in a pipeline runs in a subshell, so its `die` cannot exit this
# script. Re-verify here, in THIS shell, so a mismatch is fatal for real.
artifact_list | while IFS="$(printf '\t')" read -r file want; do
  [ "$(sha_of "$WORK/$file")" = "$want" ] || exit 17
done || die "one or more artifacts failed verification (see above) — no engine was touched"
log "all $count artifact(s) verified"

# --- 3: engines -------------------------------------------------------------

if wants postgres && require_client postgres psql; then
  step "postgres"
  : "${PGHOST:?PGHOST must name the Postgres host}"
  : "${PGUSER:?PGUSER must name a superuser able to CREATE DATABASE and CREATE ROLE}"
  : "${SAMPLE_PG_PASSWORD:?SAMPLE_PG_PASSWORD must be set — it is the password of the demo login}"
  SAMPLE_PG_DB="${SAMPLE_PG_DB:-dp_sample_trips}"
  require_sql_safe SAMPLE_PG_PASSWORD "$SAMPLE_PG_PASSWORD"
  require_sql_safe SAMPLE_PG_DB "$SAMPLE_PG_DB"
  LOADED_ANY=1

  psql_admin() { psql -v ON_ERROR_STOP=1 -qtA -d "${PGDATABASE:-postgres}" "$@"; }
  psql_sample() { psql -v ON_ERROR_STOP=1 -qtA -d "$SAMPLE_PG_DB" "$@"; }

  exists=$(psql_admin -c "SELECT 1 FROM pg_database WHERE datname = '$SAMPLE_PG_DB'")
  if [ "$exists" != "1" ]; then
    log "  creating database $SAMPLE_PG_DB"
    psql_admin -c "CREATE DATABASE $SAMPLE_PG_DB"
  fi

  loaded=$(psql_sample -c "SELECT version FROM _sample_meta LIMIT 1" 2>/dev/null || true)
  if [ "$loaded" = "$VERSION" ]; then
    log "  SKIP — _sample_meta already records version $VERSION"
  else
    log "  pg_restore $SAMPLE_PG_DB"
    # --clean --if-exists (023 F2): without it an INTERRUPTED restore left tables
    # behind with no marker, and every later run died on "already exists" — the
    # documented v1->v2 upgrade path (marker names a different version, restore
    # runs over the old tables) was the same wedge. Dropping what exists first
    # makes a re-run always safe, which is the loader's stated contract.
    pg_restore --clean --if-exists --no-owner --no-privileges --exit-on-error \
      -d "$SAMPLE_PG_DB" "$WORK/pg-trips.dump"

    log "  creating SELECT-only login $DEMO_USER"
    # Created, not assumed (design §5). The grants are the whole point: SELECT on
    # existing tables, USAGE on the schema, and DEFAULT PRIVILEGES so a later
    # artifact version's tables are readable too — and nothing else. No CREATE on
    # the schema, so the login cannot make itself a table to write to.
    psql_sample <<EOSQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$DEMO_USER') THEN
    CREATE ROLE $DEMO_USER LOGIN PASSWORD '$SAMPLE_PG_PASSWORD';
  ELSE
    ALTER ROLE $DEMO_USER LOGIN PASSWORD '$SAMPLE_PG_PASSWORD';
  END IF;
END
\$\$;
REVOKE ALL ON DATABASE $SAMPLE_PG_DB FROM PUBLIC;
GRANT CONNECT ON DATABASE $SAMPLE_PG_DB TO $DEMO_USER;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO $DEMO_USER;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO $DEMO_USER;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO $DEMO_USER;
EOSQL

    # The marker is written LAST: an interrupted restore leaves no marker and the
    # next run redoes the engine from scratch.
    psql_sample <<EOSQL
CREATE TABLE IF NOT EXISTS _sample_meta (
    version     TEXT        NOT NULL,
    loaded_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
DELETE FROM _sample_meta;
INSERT INTO _sample_meta (version) VALUES ('$VERSION');
GRANT SELECT ON _sample_meta TO $DEMO_USER;
EOSQL
    log "  loaded, marker set to $VERSION"
  fi
fi

if wants mysql && require_client mysql mysql; then
  step "mysql"
  : "${MYSQL_HOST:?MYSQL_HOST must name the MySQL host}"
  : "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD must be set — the loader creates a database and a role}"
  : "${SAMPLE_MYSQL_PASSWORD:?SAMPLE_MYSQL_PASSWORD must be set — it is the password of the demo login}"
  SAMPLE_MYSQL_DB="${SAMPLE_MYSQL_DB:-dp_sample_weather}"
  require_sql_safe SAMPLE_MYSQL_PASSWORD "$SAMPLE_MYSQL_PASSWORD"
  require_sql_safe SAMPLE_MYSQL_DB "$SAMPLE_MYSQL_DB"
  LOADED_ANY=1

  # MYSQL_PWD rather than -p on the command line: an argv password is visible in
  # `ps` to every process in the container.
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; export MYSQL_PWD
  my() { mysql --protocol=TCP -h "$MYSQL_HOST" -u root --batch --silent "$@"; }

  loaded=$(my -D "$SAMPLE_MYSQL_DB" -e "SELECT version FROM _sample_meta LIMIT 1" 2>/dev/null || true)
  if [ "$loaded" = "$VERSION" ]; then
    log "  SKIP — _sample_meta already records version $VERSION"
  else
    log "  restoring $SAMPLE_MYSQL_DB"
    # The dump carries CREATE DATABASE (mysqldump --databases), so no createdb step.
    gzip -dc "$WORK/mysql-weather.sql.gz" | my

    log "  creating SELECT-only login $DEMO_USER"
    my <<EOSQL
CREATE USER IF NOT EXISTS '$DEMO_USER'@'%' IDENTIFIED BY '$SAMPLE_MYSQL_PASSWORD';
ALTER USER '$DEMO_USER'@'%' IDENTIFIED BY '$SAMPLE_MYSQL_PASSWORD';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '$DEMO_USER'@'%';
GRANT SELECT ON \`$SAMPLE_MYSQL_DB\`.* TO '$DEMO_USER'@'%';
FLUSH PRIVILEGES;
EOSQL

    my -D "$SAMPLE_MYSQL_DB" <<EOSQL
CREATE TABLE IF NOT EXISTS _sample_meta (
    version   VARCHAR(32) NOT NULL,
    loaded_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
DELETE FROM _sample_meta;
INSERT INTO _sample_meta (version) VALUES ('$VERSION');
EOSQL
    log "  loaded, marker set to $VERSION"
  fi
fi

if wants sqlite; then
  step "sqlite + app-visible files"
  LOADED_ANY=1
  # No server, no login: the file is mounted read-only into the app container and
  # the datasource sets the driver's read-only open mode as well
  # (datasources.md §8A.4). "Loading" is placing the verified file.
  #
  # A marker table is meaningless for a file artifact — the file's SHA-256 IS the
  # marker, and it was verified above. Copy only when the destination differs, so
  # a re-run of an unchanged version is a no-op the app never notices.
  for f in nyc_reference.db examples.json; do
    if [ ! -f "$SAMPLE_DIR/$f" ] || [ "$(sha_of "$SAMPLE_DIR/$f")" != "$(sha_of "$WORK/$f")" ]; then
      cp "$WORK/$f" "$SAMPLE_DIR/$f.part"
      mv "$SAMPLE_DIR/$f.part" "$SAMPLE_DIR/$f"
      log "  placed $SAMPLE_DIR/$f"
    else
      log "  SKIP — $SAMPLE_DIR/$f already matches the verified artifact"
    fi
  done
  chmod 0444 "$SAMPLE_DIR/nyc_reference.db" "$SAMPLE_DIR/examples.json"

  # The bootstrap datasources file is REPO content, not a published artifact: it
  # names this deployment's hosts and its credential placeholders, which are
  # deployment facts, not dataset facts. It is copied onto the same volume so the
  # app needs exactly ONE mount — adding a second bind mount to the app service
  # would enlarge the non-demo config for a file the non-demo path never reads.
  if [ -f "$SCRIPT_DIR/bootstrap-datasources.yml" ]; then
    cp "$SCRIPT_DIR/bootstrap-datasources.yml" "$SAMPLE_DIR/bootstrap-datasources.yml.part"
    mv "$SAMPLE_DIR/bootstrap-datasources.yml.part" "$SAMPLE_DIR/bootstrap-datasources.yml"
    chmod 0444 "$SAMPLE_DIR/bootstrap-datasources.yml"
    log "  placed $SAMPLE_DIR/bootstrap-datasources.yml"
  fi
fi

# F8's silent-success case: a run that loaded NO engine at all is a failure —
# the default engine set means "every engine this image can reach", and an image
# that can reach none of them has not loaded the demo.
if [ "$LOADED_ANY" -ne 1 ]; then
  die "no engine was loaded — no client binary for any requested engine was found in this image"
fi

step "load complete (version $VERSION)"
