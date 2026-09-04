#!/usr/bin/env bash
# scripts/sample-data/lib/engines.sh — throwaway build engines and the content
# checksums, shared by load-and-dump.sh (which produces the artifacts) and
# verify.sh (which re-derives the same numbers from them).
#
# SOURCED after lib/common.sh.
#
# Sharing this file is the point: if verify.sh computed checksums its own way,
# a green verify would only prove the two implementations agree, not that the
# artifact holds what the manifest says.
#
# PROJECT SCOPING (MISTAKES.md, 2026-08-28). Every container created here is
# named "$SD_PROJECT-<role>-<tag>" and removed by trap. Nothing in this file
# uses `docker compose`, so it cannot collide with a compose project at all —
# but the naming still keeps a stray container obviously ours.

# --- container lifecycle ----------------------------------------------------

# Cleanup removes ONLY THIS INVOCATION'S containers, by the per-run prefix
# "$SD_PROJECT-$$-".
#
# The obvious form — an array the start functions push onto — is silently broken
# here: every start function is called as `PG=$(engine_start_postgres …)`, i.e.
# in a COMMAND SUBSTITUTION, which is a subshell. The append happens in the
# subshell and is lost; the trap then finds an empty array and removes nothing,
# leaving throwaway Postgres and MySQL containers running on a shared box.
# (Observed: two orphans after a clean, successful build.)
#
# The name prefix is deterministic and survives subshells — `$$` does NOT change
# in a subshell (BASHPID does), so command substitutions still produce the same
# names as the trap's filter. And it is PER-INVOCATION (023 F9): the previous
# shared `$SD_PROJECT-` prefix made one script's EXIT trap kill a concurrent
# sibling's live engines — a verify.sh started while load-and-dump.sh was
# restoring tore the build's engines out from under it. `$SD_PROJECT` keeps the
# containers obviously ours; `$$` keeps them obviously MINE.
sd_cleanup_engines() {
  local c
  for c in $(docker ps -aq --filter "name=^${SD_PROJECT}-$$-" 2>/dev/null); do
    docker rm -f "$c" >/dev/null 2>&1 || true
  done
}

# engine_start_postgres <tag> — start a throwaway Postgres, echo its container
# name. The image is DIGEST-pinned in sources.lock: pg_dump's custom format is
# tied to the server major, and the demo compose restores with the same major,
# so "whatever postgres:16-alpine resolves to today" is not good enough.
engine_start_postgres() {
  local tag="$1"
  local name="${SD_PROJECT}-$$-pg-$tag"
  docker rm -f "$name" >/dev/null 2>&1 || true
  docker run -d --name "$name" \
    -e POSTGRES_DB=dp_sample_trips \
    -e POSTGRES_USER=build \
    -e POSTGRES_PASSWORD=build \
    -e POSTGRES_HOST_AUTH_METHOD=trust \
    "$(image_ref postgres)" >/dev/null
  local i
  # Probe over TCP (-h 127.0.0.1), never the unix socket.
  #
  # The postgres entrypoint runs a TEMPORARY server for initdb with
  # `listen_addresses=''` — socket only — then SHUTS IT DOWN and starts the real
  # one. A socket `pg_isready` therefore reports READY during initialisation, and
  # the very next statement can hit "FATAL: the database system is shutting down"
  # (observed mid-build). TCP is served only by the real server, which is exactly
  # the moment we want. Same trap, same fix, as engine_start_mysql below.
  for i in $(seq 1 120); do
    if docker exec "$name" pg_isready -h 127.0.0.1 -U build -d dp_sample_trips >/dev/null 2>&1; then
      echo "$name"; return 0
    fi
    sleep 1
  done
  docker logs --tail 30 "$name" >&2 || true
  die "throwaway Postgres '$name' never became ready"
}

# engine_start_mysql <tag>
engine_start_mysql() {
  local tag="$1"
  local name="${SD_PROJECT}-$$-mysql-$tag"
  docker rm -f "$name" >/dev/null 2>&1 || true
  docker run -d --name "$name" \
    -e MYSQL_DATABASE=dp_sample_weather \
    -e MYSQL_ROOT_PASSWORD=build \
    "$(image_ref mysql)" \
    --local-infile=1 >/dev/null
  local i
  # Probe over TCP, with the real password, against the real database.
  #
  # The obvious probes are all wrong here: the MySQL entrypoint runs a TEMPORARY
  # server during initialisation that listens on the unix socket only and has no
  # root password yet, so a socket `mysqladmin ping` reports READY while
  # `mysql -uroot` with the configured password is still Access-denied. TCP is
  # not served until the real server starts, which is exactly the moment we want.
  for i in $(seq 1 180); do
    if docker exec -e MYSQL_PWD=build "$name" \
         mysql --protocol=TCP -h 127.0.0.1 -uroot -D dp_sample_weather -e 'SELECT 1' >/dev/null 2>&1; then
      echo "$name"; return 0
    fi
    sleep 1
  done
  docker logs --tail 30 "$name" >&2 || true
  die "throwaway MySQL '$name' never became ready"
}

# engine_start_mysql_db <tag> <dbname> / mysql_in_db — the generic pair the
# trade family (scripts/sample-data-trade/) uses; engine_start_mysql above is
# the dp_sample_weather-specific wrapper kept for the mobility callers. The
# TCP-probe reasoning is identical.
engine_start_mysql_db() {
  local tag="$1" db="$2"
  local name="${SD_PROJECT}-$$-mysql-$tag"
  docker rm -f "$name" >/dev/null 2>&1 || true
  docker run -d --name "$name" \
    -e MYSQL_DATABASE="$db" \
    -e MYSQL_ROOT_PASSWORD=build \
    "$(image_ref mysql)" \
    --local-infile=1 >/dev/null
  local i
  for i in $(seq 1 180); do
    if docker exec -e MYSQL_PWD=build "$name" \
         mysql --protocol=TCP -h 127.0.0.1 -uroot -D "$db" -e 'SELECT 1' >/dev/null 2>&1; then
      echo "$name"; return 0
    fi
    sleep 1
  done
  docker logs --tail 30 "$name" >&2 || true
  die "throwaway MySQL '$name' never became ready"
}

mysql_in_db() {
  local c="$1" db="$2"; shift 2
  docker exec -i -e MYSQL_PWD=build "$c" \
    mysql --protocol=TCP -h 127.0.0.1 -uroot --database="$db" "$@"
}

# psql_in <container> <args...> — psql as the build superuser, errors fatal.
# ON_ERROR_STOP is not optional: without it psql reports success after a failed
# statement, and a half-loaded table would be dumped and published.
psql_in() {
  local c="$1"; shift
  docker exec -i "$c" psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U build -d dp_sample_trips "$@"
}

# mysql_in <container> <args...>
mysql_in() {
  local c="$1"; shift
  docker exec -i -e MYSQL_PWD=build "$c" \
    mysql --protocol=TCP -h 127.0.0.1 -uroot --database=dp_sample_weather "$@"
}

# --- content checksums ------------------------------------------------------
#
# The determinism contract (design §4.1) is about table CONTENTS, not dump
# bytes: pg_dump output legitimately differs between tool builds. So the
# manifest records, per table, the row count and a checksum of the engine's own
# ordered row stream.
#
# The stream is produced BY the engine (an ordered SELECT of the whole table)
# and hashed outside it. Hashing outside is deliberate and uniform: SQLite has
# no md5(), MySQL's GROUP_CONCAT silently truncates at group_concat_max_len,
# and Postgres would have to buffer a 5M-row string — three different
# workarounds for one property. Streaming the ordered rows through shasum has
# none of those failure modes and is byte-identical across the two builds that
# the determinism proof compares.
#
# The table list and its ordering key live in checksums.spec so the producer
# and the verifier cannot drift.

# EVERY function below redirects stdin from /dev/null. `docker exec -i` READS
# STDIN, and these run inside `while read` loops over checksums.spec — without
# the redirect the client swallows the loop's remaining input and only the FIRST
# table of each engine is ever fingerprinted. (Observed: a checksums.tsv with one
# row where the spec declares three. The manifest would have been silently
# incomplete and verify.sh would have agreed with it.)

# checksum_pg <container> <table> <order_by>
checksum_pg() {
  local c="$1" t="$2" o="$3"
  psql_in "$c" -At -F '|' -c "SELECT * FROM $t ORDER BY $o" < /dev/null | shasum -a 256 | awk '{print $1}'
}

# checksum_mysql <container> <table> <order_by>
checksum_mysql() {
  local c="$1" t="$2" o="$3"
  mysql_in "$c" -N --batch -e "SELECT * FROM $t ORDER BY $o" < /dev/null | shasum -a 256 | awk '{print $1}'
}

# checksum_sqlite <file> <table> <order_by>
checksum_sqlite() {
  local f="$1" t="$2" o="$3"
  sqlite3 -separator '|' -noheader "$f" "SELECT * FROM $t ORDER BY $o;" < /dev/null | shasum -a 256 | awk '{print $1}'
}

# count_pg / count_mysql / count_sqlite <handle> <table>
count_pg()     { psql_in "$1" -At -c "SELECT count(*) FROM $2" < /dev/null; }
count_mysql()  { mysql_in "$1" -N --batch -e "SELECT count(*) FROM $2" < /dev/null; }
count_sqlite() { sqlite3 -noheader "$1" "SELECT count(*) FROM $2;" < /dev/null; }

# checksum_duckdb <dbfile> <table> <order_by> / count_duckdb — the fourth
# engine, used by the trade family. The .duckdb FILE is the artifact (like
# SQLite), queried through the same pinned DuckDB CLI that built it
# (duckdb_bin, from common.sh — both families pin the CLI version in their
# own sources.lock, and this is only called for spec rows that name engine
# `duckdb`, which only the trade family's checksums.spec does). Hashing
# stays OUTSIDE the engine for the same reasons as everywhere above.
# Rendering: -noheader -list streams `|`-separated values; DECIMAL and
# TIMESTAMP render deterministically inside one pinned CLI version, which is
# the same determinism boundary the whole contract already claims.
checksum_duckdb() {
  local f="$1" t="$2" o="$3"
  "$(duckdb_bin)" -noheader -list "$f" "SELECT * FROM $t ORDER BY $o;" < /dev/null | shasum -a 256 | awk '{print $1}'
}
count_duckdb() { "$(duckdb_bin)" -noheader -list "$1" "SELECT count(*) FROM $2;" < /dev/null; }

# checksum_rows <engine> <handle> — emits "engine<TAB>table<TAB>count<TAB>sha256"
# for every table checksums.spec declares for that engine.
checksum_rows() {
  local engine="$1" handle="$2" e t o n ck
  while read -r e t o; do
    [ "$e" = "$engine" ] || continue
    case "$engine" in
      postgres) n=$(count_pg "$handle" "$t");     ck=$(checksum_pg "$handle" "$t" "$o") ;;
      mysql)    n=$(count_mysql "$handle" "$t");  ck=$(checksum_mysql "$handle" "$t" "$o") ;;
      sqlite)   n=$(count_sqlite "$handle" "$t"); ck=$(checksum_sqlite "$handle" "$t" "$o") ;;
      duckdb)   n=$(count_duckdb "$handle" "$t"); ck=$(checksum_duckdb "$handle" "$t" "$o") ;;
      *) die "unknown engine '$engine' in checksums.spec" ;;
    esac
    printf '%s\t%s\t%s\t%s\n' "$engine" "$t" "$n" "$ck"
  done <<< "$(grep -v '^[[:space:]]*#' "$SD_ROOT/checksums.spec" | grep -v '^[[:space:]]*$')"
}
