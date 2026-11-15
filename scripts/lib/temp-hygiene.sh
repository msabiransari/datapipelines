#!/usr/bin/env bash
# shellcheck shell=bash
# temp-hygiene.sh — test temp files: the checkout's own root, and a bounded sweep (#983,
# DEVELOPMENT.md §9.7).
#
# Sourced by scripts/gate.sh and scripts/pregate.sh, which call th::step AFTER
# verification::lock holds (one live holder per checkout, so no gate or pregate of this
# checkout is running):
#   B.1  empty THIS checkout's test temp root (no age), except what a live process uses;
#   B.2  sweep the shared temp directory: exact named families only (TH_FAMILIES), owned by
#        this uid, older than VERIFICATION_TMP_SWEEP_AGE_S, not used by a live process;
#   B.2a the roots of checkouts that are gone, by the record beside each root.
# A FILE is removed only under a kernel write lease (temp-hygiene-helper.py): never on a
# name, a command line or a /proc read. A DIRECTORY a readable live process references —
# as cwd, an open handle or on its command line, itself or by a path below it — is kept
# whole: a command-line path counts by what it RESOLVES to (the kernel's own lookups, through
# the process's root and cwd, links followed), never by how it is spelled, and a reference
# that cannot be resolved for certain keeps every candidate (temp-hygiene-helper.py Resolver).
# A live process that cannot be read keeps no directory (owner ruling 2026-11-14:
# only EMPTY directories can go on that assumption; every summary line counts it).
# A reference that resolves onto FUSE (type "fuse" or "fuse.*" in the kernel's mount table) is
# judged in that filesystem's own tree, so it keeps no candidate and is not doubt (owner ruling
# 2026-11-15; every summary line counts such processes as fuse-processes=). Its cost: a FUSE
# program that takes its source from a configuration file, not its command line, and serves a
# file from inside a test temp directory would have that directory's closed files swept. These
# are the only two exceptions to "a reference of a live process keeps".
#
# Modes: VERIFICATION_TMP_SWEEP=real (default in the wrappers): B.1 and the sweep remove.
#        VERIFICATION_TMP_SWEEP=dry-run: B.1 removes, the sweep only lists. Anything else is
#        dry-run, with one stderr line.
# Manual: ./scripts/lib/temp-hygiene.sh --dry-run   lists what BOTH parts would remove;
#         ./scripts/lib/temp-hygiene.sh --print-root prints this checkout's root.
#         There is no manual real run.
# Knobs: VERIFICATION_TMP_SWEEP_AGE_S (default 3600, whole number >= 0),
#        VERIFICATION_TMP_BUDGET_S (default 60, whole number >= 1): ONE budget over the scan,
#        B.1, the sweep and the counting. A failure of the step warns and never fails a run.
#
# Seams for the self-test (scripts/temp-hygiene-test.sh) are shell functions it replaces
# after sourcing: th::_now (the pass's age clock), th::_clock (the budget clock),
# th::_proc_refs (one process's read), th::_lease_mode, th::_remover_extra_args; case 47 also
# sets TH_LIB_DIR in the pass's subshell (the real helper behind a wrapper). TH_LIB_DIR is
# assigned when this file is sourced, so no environment variable can switch off or redirect
# the lease test or the process scan.

TH_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TH_AGE_DEFAULT_S=3600
TH_BUDGET_DEFAULT_S=60
TH_PARENT_PREFIX=dp-test-tmp-
TH_RECORD_SUFFIX=.checkout
TH_KEY_HEX_DIGITS=16

# The allowlist: '<type: f file, d directory> <label> <whole-name extended regex>'. A name not on it is
# never touched. Each family is traced to the code that creates it (DEVELOPMENT.md §9.7);
# families seen in /tmp that could not be traced are listed there as "not swept".
TH_FAMILIES=(
  'f libduckdb_java libduckdb_java[0-9]+\.so'
  'd tomcat tomcat\.[0-9]{1,5}\.[0-9]+'
  'd tomcat-docbase tomcat-docbase\.[0-9]{1,5}\.[0-9]+'
  'd legacy-row-e2e legacy-row-e2e[0-9]+'
  'd endpoint-migration-e2e endpoint-migration-e2e[0-9]+'
  'd demo-api-key-e2e demo-api-key-e2e[0-9]+'
  'd keys-v2-e2e keys-v2-e2e[0-9]+'
  'd bootstrap-e2e bootstrap-e2e[0-9]+'
  'd sweep186-roots sweep186-roots[0-9]+'
  'd dp845-egress dp845-egress[0-9]+'
  'd lake-*-it lake-(connectivity|minio|790|778|mi2|browser)-it[0-9]+'
  'd dp-roots* dp-roots(-real|-outside|-gone|-l4|-l4-narrow)?[0-9]+'
  'd dp-outside-l4 dp-outside-l4[0-9]+'
  'd dp-*-pool dp-(h2|l2)-pool[0-9]+'
  'd h2-session h2-session[0-9]+'
  'd jar-smoke-*-isolate jar-smoke-(no|enabled)-isolate[0-9]+'
  'f jar-smoke*.log jar-smoke(-agreement|-js-timeout)?[0-9]+\.log'
  'f entry-inventory-app*.log entry-inventory-app[0-9]+\.log'
  'd pregate-safety pregate-safety\.[A-Za-z0-9]{6}'
  'd pgres-selftest pgres-selftest\.[A-Za-z0-9]{6}'
  'd temp-hygiene-test temp-hygiene-test\.[A-Za-z0-9]{6}'
)
# Never touched, whatever a family pattern says: checked BEFORE the allowlist.
TH_NEVER_TOUCH='^(\..*|claude-.*|com\.google\.Chrome.*|hsperfdata_.*|systemd-private-.*|playwright-java-.*|opencode|dp-test-tmp-.*)$'

# ---- seams --------------------------------------------------------------------------
th::_now() { printf '%(%s)T\n' -1; }    # the pass's ONE age clock reading
th::_clock() { printf -v TH_CLOCK '%(%s)T' -1; }  # the budget clock: sets TH_CLOCK, no fork
th::_lease_mode() { echo try; }         # "off" keeps every file (self-test only)
th::_remover_extra_args() { :; }        # extra remover arguments (self-test only: --stop-after)

th::_warn() { echo "temp-hygiene: WARNING $*" >&2; }

# Whole number within [min, 999999999] or the default with one stderr line.
th::_whole() { # name value default min
  local name="$1" raw="$2" default="$3" min="$4"
  if [ -z "$raw" ]; then
    printf '%s\n' "$default"
  elif [[ "$raw" =~ ^[0-9]{1,9}$ ]] && [ "$((10#$raw))" -ge "$min" ]; then
    printf '%s\n' "$((10#$raw))"
  else
    echo "temp-hygiene: $name='$raw' is not a whole number >= $min; using the default $default" >&2
    printf '%s\n' "$default"
  fi
}

# ---- the derivation rule (TestTempRoot in CommonConventionsPlugin.kt) ------------------
th::_realdir() { (cd -P -- "$1" 2>/dev/null && pwd -P); }

th::temp_base() {
  local named="${TMPDIR-}"
  if [ -n "$named" ] && [[ "$named" == /* ]] && [ -d "$named" ]; then
    th::_realdir "$named"
  else
    th::_realdir /tmp
  fi
}

th::_sha256() { # stdin → hex digest
  if command -v sha256sum >/dev/null 2>&1; then sha256sum | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 | cut -d' ' -f1
  else python3 -c 'import hashlib,sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())'
  fi
}

th::checkout_key() { # checkout → key
  local real digest
  real="$(th::_realdir "$1")" || return 1
  [ -n "$real" ] || return 1
  digest="$(printf '%s' "$real" | th::_sha256)" || return 1
  printf '%s\n' "${digest:0:$TH_KEY_HEX_DIGITS}"
}

th::parent_dir() { printf '%s/%s%s\n' "$1" "$TH_PARENT_PREFIX" "$(id -u)"; } # base

th::print_root() { # checkout → root
  local base key
  base="$(th::temp_base)" || return 1
  key="$(th::checkout_key "$1")" || return 1
  printf '%s/%s\n' "$(th::parent_dir "$base")" "$key"
}

# A private directory: a real directory (not a link), owned by this uid, no group/other write.
th::_private_dir_problem() { # dir → prints the problem, returns 1; silent 0 when private
  local info uid type mode
  info="$(stat -c '%u|%a|%F' -- "$1" 2>/dev/null)" || { echo "cannot be read"; return 1; }
  IFS='|' read -r uid mode type <<< "$info"
  [ "$type" != "symbolic link" ] || { echo "is a symbolic link"; return 1; }
  [ "$type" = directory ] || { echo "is not a directory ($type)"; return 1; }
  [ "$uid" = "${TH_UID:-$(id -u)}" ] || { echo "is owned by uid $uid, not ${TH_UID:-$(id -u)}"; return 1; }
  [ $((8#$mode & 8#022)) -eq 0 ] || { echo "is writable by group or others (mode $mode)"; return 1; }
  return 0
}

# ---- budget ---------------------------------------------------------------------------
# ONE budget over the step: checked for every entry visited (kept or removed), between the
# processes and references of the scan, around each listing, and in record cleanup. The
# helper gets the same deadline (TH_DEADLINE_REAL) and checks it for every entry it reaches.
th::_budget_left() { th::_clock; [ "$TH_CLOCK" -lt "$TH_DEADLINE" ]; }

# th::_entries <dir> <find -printf format>: one level of <dir> into TH_ENTRIES. The listing
# itself is bounded by what remains of the budget; 1 when the budget ended (TH_ENTRIES is then
# empty: nothing listed part-way is acted on).
th::_entries() {
  local dir="$1" format="$2" remaining status
  TH_ENTRIES=()
  th::_budget_left || return 1
  remaining=$(( TH_DEADLINE_REAL - $(printf '%(%s)T' -1) ))
  [ "$remaining" -gt 0 ] || return 1
  if command -v timeout >/dev/null 2>&1; then
    mapfile -d '' TH_ENTRIES < <(timeout -s KILL "$remaining" find "$dir" -mindepth 1 -maxdepth 1 -printf "$format" 2>/dev/null; printf 'rc=%s\0' "$?")
  else
    mapfile -d '' TH_ENTRIES < <(find "$dir" -mindepth 1 -maxdepth 1 -printf "$format" 2>/dev/null; printf 'rc=%s\0' "$?")
  fi
  status="${TH_ENTRIES[${#TH_ENTRIES[@]}-1]:-}"
  unset 'TH_ENTRIES[${#TH_ENTRIES[@]}-1]'
  case "$status" in rc=124|rc=137) TH_ENTRIES=(); return 1 ;; esac
  return 0
}

# ---- the process scan -----------------------------------------------------------------
# One read of every process (temp-hygiene-helper.py scan), kept as a snapshot; th::_proc_refs
# answers for one process from it, so the self-test can make a process unreadable, gone or a
# zombie by replacing that one function. Nothing the scan reads can make a FILE removable.
th::_snapshot() { # base candidate-dir... → TH_SNAP_* ; 0 complete, 1 stopped at the budget, 2 not possible
  local base="$1" records=() record kind pid="" rc dir dirs=()
  shift
  for dir in "$@"; do dirs+=(--candidates-in "$dir"); done
  unset TH_SNAP_RC TH_SNAP_NAME TH_SNAP_REF_INDEX
  declare -gA TH_SNAP_RC=() TH_SNAP_NAME=() TH_SNAP_REF_INDEX=()
  TH_SNAP_PIDS=(); TH_SNAP_REF_PID=(); TH_SNAP_REF_KIND=(); TH_SNAP_REF_VALUE=()
  [ -r /proc/self/stat ] && command -v python3 >/dev/null 2>&1 || return 2
  mapfile -d '' records < <(python3 "$TH_LIB_DIR/temp-hygiene-helper.py" scan --base "$base" --deadline "$TH_DEADLINE_REAL" "${dirs[@]}" 9>&- 10>&- 11>&-; printf 'rc=%s\0' "$?")
  [ "${records[${#records[@]}-1]:-}" = rc=0 ] || return 2
  for record in "${records[@]:0:${#records[@]}-1}"; do
    case "$record" in
      '#budget') return 1 ;;
      P$'\t'*)
        record="${record#P$'\t'}"; pid="${record%%$'\t'*}"; record="${record#*$'\t'}"
        rc="${record%%$'\t'*}"
        TH_SNAP_PIDS+=("$pid"); TH_SNAP_RC["$pid"]="$rc"; TH_SNAP_NAME["$pid"]="${record#*$'\t'}" ;;
      R$'\t'*)
        record="${record#R$'\t'}"; kind="${record%%$'\t'*}"
        TH_SNAP_REF_INDEX["$pid"]+="${#TH_SNAP_REF_PID[@]} "
        TH_SNAP_REF_PID+=("$pid"); TH_SNAP_REF_KIND+=("$kind"); TH_SNAP_REF_VALUE+=("${record#*$'\t'}") ;;
    esac
  done
  return 0
}

# th::_proc_refs <pid>: one process's read. Fills TH_P_REFS (cwd and fd targets under the base,
# and every candidate directory a reference of it resolves to or below), sets TH_PROC_NAME and
# TH_P_DOUBT (how many of its references were in doubt: each named every candidate) and TH_P_FUSE
# (how many resolved onto FUSE: judged in that filesystem's own tree, kept nothing). Returns 0 read,
# 2 live but cwd or fd unreadable, 3 gone, 4 zombie, 5 kernel thread. The caller keeps the
# references of 0 and 2 only: a gone process or a zombie holds nothing.
th::_proc_refs() {
  local pid="$1" i
  TH_PROC_NAME="${TH_SNAP_NAME[$pid]:-?}"
  for i in ${TH_SNAP_REF_INDEX[$pid]:-}; do
    case "${TH_SNAP_REF_KIND[$i]}" in
      d) TH_P_DOUBT="${TH_SNAP_REF_VALUE[$i]}" ;;
      u) TH_P_FUSE="${TH_SNAP_REF_VALUE[$i]}" ;;
      *) TH_P_REFS+=("${TH_SNAP_REF_VALUE[$i]}") ;;
    esac
  done
  return "${TH_SNAP_RC[$pid]:-3}"
}

# Fills TH_REFS, TH_UNREADABLE(_NAMES), TH_DOUBTFUL(_NAMES), TH_FUSE(_NAMES). Returns 0 complete,
# 1 stopped at the budget, 2 not possible (no /proc, no python3).
th::_scan_processes() { # base candidate-dir... (the directories whose entries may be candidates)
  local base="$1" pid rc
  TH_REFS=(); TH_UNREADABLE=0; TH_UNREADABLE_NAMES=(); TH_DOUBTFUL=0; TH_DOUBTFUL_NAMES=(); TH_FUSE=0; TH_FUSE_NAMES=()
  rc=0; th::_snapshot "$@" || rc=$?
  [ "$rc" -eq 0 ] || return "$rc"
  for pid in "${TH_SNAP_PIDS[@]}"; do
    th::_budget_left || return 1
    TH_PROC_NAME=; TH_P_REFS=(); TH_P_DOUBT=0; TH_P_FUSE=0
    rc=0; th::_proc_refs "$pid" || rc=$?
    case "$rc" in 0|2) TH_REFS+=("${TH_P_REFS[@]}") ;; *) TH_P_DOUBT=0; TH_P_FUSE=0 ;; esac
    if [ "$TH_P_DOUBT" -gt 0 ]; then
      TH_DOUBTFUL=$((TH_DOUBTFUL + 1))
      [ "${#TH_DOUBTFUL_NAMES[@]}" -ge 5 ] || TH_DOUBTFUL_NAMES+=("$pid ${TH_PROC_NAME:-?}")
    fi
    if [ "$TH_P_FUSE" -gt 0 ]; then
      TH_FUSE=$((TH_FUSE + 1))
      [ "${#TH_FUSE_NAMES[@]}" -ge 5 ] || TH_FUSE_NAMES+=("$pid ${TH_PROC_NAME:-?}")
    fi
    if [ "$rc" -eq 2 ]; then
      TH_UNREADABLE=$((TH_UNREADABLE + 1))
      [ "${#TH_UNREADABLE_NAMES[@]}" -ge 5 ] || TH_UNREADABLE_NAMES+=("$pid ${TH_PROC_NAME:-?}")
    fi
  done
  th::_index_refs "$base" || return 1
  return 0
}

# A directory is referenced when a read process has it, or a path below it, as cwd or an
# open handle, or names it on its command line. th::_index_refs turns TH_REFS into TH_REF_SET
# once per pass, so a lookup is one hash probe. Every entry of TH_REFS is an exact path: a cwd
# or handle under the base as the kernel prints it (it and each ancestor below the base are
# indexed), or a candidate directory the helper found by RESOLVING a reference — its arguments
# read with every possible start and end, each looked up by the kernel through the process's
# own root and cwd, and matched by device and inode along the end's ".." chain (spelling plays
# no part: "..", ".", "//", a symlinked base or component, a relative path, any depth, another
# mount namespace). A reference the helper cannot resolve for certain names every candidate.
th::_add_ancestors() { # base path
  local base="$1" path="$2"
  while [[ "$path" == "$base"/* ]]; do
    path="${path%/}"
    [[ "$path" == "$base"/* ]] || break
    TH_REF_SET["$path"]=1
    path="${path%/*}"
  done
}

th::_index_refs() { # base → 0 indexed, 1 stopped at the budget
  local base="$1" x
  unset TH_REF_SET
  declare -gA TH_REF_SET=()
  for x in "${TH_REFS[@]}"; do th::_budget_left || return 1; th::_add_ancestors "$base" "$x"; done
}

th::_referenced() { [ -n "${TH_REF_SET[$1]:-}" ]; } # path

# The kernel's list of listening TCP ports (hex → decimal), for tomcat.<port>.* of a live app.
th::_listening_ports() {
  local file slot local_address remote state rest
  TH_PORTS_OK=1; TH_PORTS=" "
  for file in /proc/net/tcp /proc/net/tcp6; do
    if [ ! -r "$file" ]; then [ "$file" = /proc/net/tcp6 ] || TH_PORTS_OK=0; continue; fi
    while read -r slot local_address remote state rest; do
      [ "$state" = 0A ] && [[ "$local_address" =~ :([0-9A-Fa-f]{1,4})$ ]] && TH_PORTS+="$((16#${BASH_REMATCH[1]})) "
    done < "$file"
  done
}

# ---- removal --------------------------------------------------------------------------
# th::_remove <mode> <part> <path...>: hands the candidates to the remover and folds its
# records into the counters of <part> (root, shared, gone) and the list file.
th::_remove() {
  local mode="$1" part="$2"; shift 2
  [ "$#" -gt 0 ] || return 0
  local records=() record status entries bytes refused untried other path lease extra=() prefix=
  [ "$mode" = real ] || prefix=would-be-
  lease="$(th::_lease_mode)"
  read -r -a extra <<< "$(th::_remover_extra_args "$part")"
  if ! command -v python3 >/dev/null 2>&1; then
    th::_warn "python3 is missing: no file can be leased, nothing is removed"
    TH_FILES_UNTRIED=$((TH_FILES_UNTRIED + $#)); return 0
  fi
  mapfile -d '' records < <(printf '%s\0' "$@" | python3 "$TH_LIB_DIR/temp-hygiene-helper.py" remove \
    --mode "$mode" --lease "$lease" --uid "$(id -u)" --deadline "$TH_DEADLINE_REAL" "${extra[@]}" 9>&- 10>&- 11>&-)
  for record in "${records[@]}"; do
    if [ "$record" = '#budget' ]; then TH_STOPPED=1; continue; fi
    IFS=$'\t' read -r status entries bytes refused untried other path <<< "$record"
    path="${record#*$'\t'*$'\t'*$'\t'*$'\t'*$'\t'*$'\t'}"
    TH_FILES_REFUSED=$((TH_FILES_REFUSED + refused))
    TH_FILES_UNTRIED=$((TH_FILES_UNTRIED + untried))
    TH_DONE["$path"]="$status"
    case "$status" in
      removed) TH_COUNT["$part-removed"]=$(( ${TH_COUNT["$part-removed"]:-0} + 1 )) ;;
      partial) TH_COUNT["$part-partial"]=$(( ${TH_COUNT["$part-partial"]:-0} + 1 )) ;;
      *)       TH_COUNT["$part-kept"]=$(( ${TH_COUNT["$part-kept"]:-0} + 1 )) ;;
    esac
    TH_COUNT["$part-bytes"]=$(( ${TH_COUNT["$part-bytes"]:-0} + bytes ))
    th::_list "$prefix$status" "$part" "$path" "bytes=$bytes refused=$refused untried=$untried other=$other"
  done
}

th::_list() { # verdict part path [detail]
  [ -n "$TH_LIST" ] || return 0
  printf '%s %s %q %s\n' "$1" "$2" "$3" "${4:-}" >> "$TH_LIST"
}

# ---- B.1 ------------------------------------------------------------------------------
th::_empty_root() { # root mode
  local root="$1" mode="$2" entries=() entry uid type name candidates=()
  [ -e "$root" ] || [ -L "$root" ] || { TH_ROOT_STATE=absent; return 0; }
  local problem
  if ! problem="$(th::_private_dir_problem "$root")"; then
    th::_warn "refusing the checkout root $root: it $problem; nothing removed through it"
    TH_ROOT_STATE=refused; return 0
  fi
  th::_entries "$root" '%U\t%y\t%P\0' || { TH_STOPPED=1; TH_ROOT_STATE=stopped; return 0; }
  entries=("${TH_ENTRIES[@]}")
  for entry in "${entries[@]}"; do
    th::_budget_left || { TH_STOPPED=1; TH_ROOT_STATE=stopped; return 0; }
    IFS=$'\t' read -r uid type _ <<< "$entry"
    name="${entry#*$'\t'*$'\t'}"
    if [ "$uid" != "$TH_UID" ]; then th::_list kept-foreign root "$root/$name"; continue; fi
    if [ "$type" = d ]; then
      if [ "$TH_SCAN" -ne 0 ]; then TH_DIRS_LIVE=$((TH_DIRS_LIVE + 1)); th::_list kept-scan-incomplete root "$root/$name"; continue; fi
      if th::_referenced "$root/$name"; then TH_DIRS_LIVE=$((TH_DIRS_LIVE + 1)); th::_list kept-live root "$root/$name"; continue; fi
    fi
    candidates+=("$root/$name")
  done
  TH_ROOT_STATE=emptied
  th::_remove "$mode" root "${candidates[@]}"
}

# ---- B.2 ------------------------------------------------------------------------------
th::_family() { # name type → TH_LABEL, 0 when on the allowlist
  local name="$1" type="$2" i
  TH_LABEL=
  [[ "$name" =~ $TH_NEVER_TOUCH ]] && return 1
  for i in "${!TH_FAMILY_REGEX[@]}"; do
    if [ "$type" = "${TH_FAMILY_TYPE[$i]}" ] && [[ "$name" =~ ^(${TH_FAMILY_REGEX[$i]})$ ]]; then TH_LABEL="${TH_FAMILY_LABEL[$i]}"; return 0; fi
  done
  return 1
}

th::_parse_families() {
  local family want label regex
  TH_FAMILY_TYPE=(); TH_FAMILY_LABEL=(); TH_FAMILY_REGEX=()
  for family in "${TH_FAMILIES[@]}"; do
    read -r want label regex <<< "$family"
    TH_FAMILY_TYPE+=("$want"); TH_FAMILY_LABEL+=("$label"); TH_FAMILY_REGEX+=("$regex")
  done
}

th::_old_enough() { # mtime(epoch, may be fractional)
  local mtime="${1%%.*}"
  [[ "$mtime" =~ ^-?[0-9]+$ ]] || return 1
  [ $((TH_NOW - mtime)) -gt "$TH_AGE" ]
}

th::_sweep_shared() { # base mode
  local base="$1" mode="$2" entries=() entry uid mtime type name label port candidates=()
  th::_entries "$base" '%U\t%T@\t%y\t%P\0' || { TH_STOPPED=1; return 0; }
  entries=("${TH_ENTRIES[@]}")
  th::_listening_ports
  for entry in "${entries[@]}"; do
    th::_budget_left || { TH_STOPPED=1; break; }
    IFS=$'\t' read -r uid mtime type _ <<< "$entry"
    name="${entry#*$'\t'*$'\t'*$'\t'}"
    th::_family "$name" "$type" || continue
    label="$TH_LABEL"
    [ "$uid" = "$TH_UID" ] || continue
    th::_old_enough "$mtime" || continue
    if [[ "$label" == tomcat* ]]; then
      port="${name#*.}"; port="${port%%.*}"
      if [ "$port" != 0 ] && { [ "$TH_PORTS_OK" -eq 0 ] || [[ "$TH_PORTS" == *" $((10#$port)) "* ]]; }; then
        TH_DIRS_LIVE=$((TH_DIRS_LIVE + 1)); th::_list kept-port-listening shared "$base/$name"; continue
      fi
    fi
    if [ "$type" = d ]; then
      if [ "$TH_SCAN" -ne 0 ]; then TH_DIRS_LIVE=$((TH_DIRS_LIVE + 1)); continue; fi
      if th::_referenced "$base/$name"; then TH_DIRS_LIVE=$((TH_DIRS_LIVE + 1)); th::_list kept-live shared "$base/$name"; continue; fi
    fi
    candidates+=("$base/$name")
  done
  # At the budget nothing more is removed: not even the candidates already collected.
  [ "$TH_STOPPED" -ne 0 ] || th::_remove "$mode" shared "${candidates[@]}"
}

# ---- B.2a -----------------------------------------------------------------------------
th::_sweep_gone_roots() { # parent mode own-key
  local parent="$1" mode="$2" own="$3" entries=() entry type name key record checkout mtime root problem
  [ -d "$parent" ] && [ ! -L "$parent" ] || return 0
  th::_entries "$parent" '%y\t%T@\t%P\0' || { TH_STOPPED=1; return 0; }
  entries=("${TH_ENTRIES[@]}")
  declare -A seen=()
  for entry in "${entries[@]}"; do
    th::_budget_left || { TH_STOPPED=1; return 0; }
    IFS=$'\t' read -r type mtime _ <<< "$entry"
    name="${entry#*$'\t'*$'\t'}"
    if [ "$type" = d ] && [[ "$name" =~ ^[0-9a-f]{$TH_KEY_HEX_DIGITS}$ ]]; then
      seen["$name"]=1
      [ "$name" != "$own" ] || continue
      record="$parent/$name$TH_RECORD_SUFFIX"
      if [ ! -f "$record" ] || [ -L "$record" ] || ! checkout="$(cat -- "$record" 2>/dev/null)" || [[ "$checkout" != /* ]]; then
        TH_UNRECORDED=$((TH_UNRECORDED + 1)); th::_list kept-unrecorded gone "$parent/$name"; continue
      fi
      [ ! -e "$checkout" ] || continue
      root="$parent/$name"
      if ! problem="$(th::_private_dir_problem "$root")"; then th::_list "kept-root-$problem" gone "$root"; continue; fi
      th::_old_enough "$mtime" || continue
      if [ "$TH_SCAN" -ne 0 ] || th::_referenced "$root"; then TH_DIRS_LIVE=$((TH_DIRS_LIVE + 1)); th::_list kept-live gone "$root"; continue; fi
      th::_remove "$mode" gone "$root"
      if [ "${TH_DONE[$root]:-}" = removed ]; then th::_drop_record "$mode" "$record"; fi
      [ "$TH_STOPPED" -eq 0 ] || return 0
    fi
  done
  # A record whose root and whose checkout are both gone.
  for entry in "${entries[@]}"; do
    th::_budget_left || { TH_STOPPED=1; return 0; }
    IFS=$'\t' read -r type _ _ <<< "$entry"
    name="${entry#*$'\t'*$'\t'}"
    [ "$type" = f ] && [[ "$name" =~ ^([0-9a-f]{$TH_KEY_HEX_DIGITS})${TH_RECORD_SUFFIX//./\\.}$ ]] || continue
    key="${BASH_REMATCH[1]}"
    [ -z "${seen[$key]:-}" ] || continue
    [ ! -e "$parent/$key" ] && [ ! -L "$parent/$key" ] || continue
    checkout="$(cat -- "$parent/$name" 2>/dev/null)" || continue
    [ ! -e "$checkout" ] || continue
    th::_drop_record "$mode" "$parent/$name"
    [ "$TH_STOPPED" -eq 0 ] || return 0
  done
}

th::_drop_record() { # mode record — LAST, and only once its root is completely gone
  local mode="$1" record="$2" root="${2%"$TH_RECORD_SUFFIX"}"
  th::_budget_left || { TH_STOPPED=1; return 0; }
  if [ "$mode" = dry-run ]; then th::_list would-remove-record gone "$record"; return 0; fi
  [ ! -e "$root" ] && [ ! -L "$root" ] || return 0
  [ -f "$record" ] && [ ! -L "$record" ] || return 0
  rm -f -- "$record" && th::_list removed-record gone "$record"
}

# ---- the step -------------------------------------------------------------------------
# th::run <checkout> <part1-mode> <shared-mode> <label> [list-file]: one pass; prints the
# summary line on stdout. Never returns non-zero for a hygiene problem.
th::run() {
  local checkout="$1" root_mode="$2" shared_mode="$3" label="$4"
  TH_LIST="${5:-}"
  local start base parent key root seconds budget scan_rc
  TH_AGE="$(th::_whole VERIFICATION_TMP_SWEEP_AGE_S "${VERIFICATION_TMP_SWEEP_AGE_S-}" "$TH_AGE_DEFAULT_S" 0)"
  budget="$(th::_whole VERIFICATION_TMP_BUDGET_S "${VERIFICATION_TMP_BUDGET_S-}" "$TH_BUDGET_DEFAULT_S" 1)"
  TH_NOW="$(th::_now)"; TH_UID="$(id -u)"; th::_parse_families
  th::_clock; start="$TH_CLOCK"; TH_DEADLINE=$((start + budget))
  TH_DEADLINE_REAL=$(( $(printf '%(%s)T' -1) + budget ))
  unset TH_REF_SET; declare -gA TH_REF_SET=()
  TH_STOPPED=0; TH_SCAN=2; TH_UNREADABLE=0; TH_UNREADABLE_NAMES=(); TH_DOUBTFUL=0; TH_DOUBTFUL_NAMES=(); TH_FUSE=0; TH_FUSE_NAMES=(); TH_REFS=(); TH_FILES_REFUSED=0; TH_FILES_UNTRIED=0; TH_DIRS_LIVE=0; TH_UNRECORDED=0; TH_ROOT_STATE=unknown
  unset TH_COUNT TH_DONE
  declare -gA TH_COUNT=() TH_DONE=()
  [ -z "$TH_LIST" ] || : > "$TH_LIST" || TH_LIST=
  base="$(th::temp_base)"; parent=; root=
  if [ -z "$base" ]; then
    th::_warn "no temp base could be resolved; nothing removed"
  else
    parent="$(th::parent_dir "$base")"
    key="$(th::checkout_key "$checkout")" || key=
    [ -z "$key" ] || root="$parent/$key"
    scan_rc=0; th::_scan_processes "$base" "$base" "$parent" ${root:+"$root"} || scan_rc=$?
    TH_SCAN="$scan_rc"
    if [ "$scan_rc" -eq 1 ]; then
      TH_STOPPED=1
    else
      [ "$scan_rc" -eq 0 ] || th::_warn "the process scan was not possible: no directory is removed or descended into"
      local parent_ok=1 problem
      if [ -e "$parent" ] || [ -L "$parent" ]; then
        if ! problem="$(th::_private_dir_problem "$parent")"; then
          th::_warn "refusing $parent: it $problem; nothing removed through it"; parent_ok=0; TH_ROOT_STATE=refused
        fi
      else
        parent_ok=0; TH_ROOT_STATE=absent
      fi
      if [ "$parent_ok" -eq 1 ] && [ -n "$root" ] && th::_budget_left; then th::_empty_root "$root" "$root_mode"; fi
      [ "$TH_STOPPED" -ne 0 ] || ! th::_budget_left || th::_sweep_shared "$base" "$shared_mode"
      [ "$TH_STOPPED" -ne 0 ] || [ "$parent_ok" -eq 0 ] || ! th::_budget_left || th::_sweep_gone_roots "$parent" "$shared_mode" "$key"
      th::_budget_left || TH_STOPPED=1
    fi
  fi
  if [ "$TH_UNREADABLE" -gt 0 ]; then
    local named
    named="$(IFS=,; printf '%s' "${TH_UNREADABLE_NAMES[*]}")"
    echo "temp-hygiene: $TH_UNREADABLE live process(es) could not be read and keep no directory (assumed not to sit in an empty test temp directory, owner ruling 2026-11-14); first five: $named" >&2
  fi
  if [ "$TH_DOUBTFUL" -gt 0 ]; then
    local doubted
    doubted="$(IFS=,; printf '%s' "${TH_DOUBTFUL_NAMES[*]}")"
    echo "temp-hygiene: $TH_DOUBTFUL live process(es) have a reference that could not be resolved for certain; each such reference keeps every candidate directory (doubt keeps); first five: $doubted" >&2
  fi
  if [ "$TH_FUSE" -gt 0 ]; then
    local fused
    fused="$(IFS=,; printf '%s' "${TH_FUSE_NAMES[*]}")"
    echo "temp-hygiene: $TH_FUSE live process(es) have a reference on a FUSE filesystem; each is judged in that filesystem's own tree and keeps no directory by it (owner ruling 2026-11-15); first five: $fused" >&2
  fi
  th::_clock; seconds=$((TH_CLOCK - start))
  local verb=removed; [ "$shared_mode" = real ] || verb=would-remove
  local root_verb=removed; [ "$root_mode" = real ] || root_verb=would-remove
  printf 'temp-hygiene: mode=%s base=%s root=%s root-state=%s root-%s=%s root-bytes=%s shared-%s=%s shared-bytes=%s gone-roots-%s=%s partial=%s files-kept-lease-refused=%s files-kept-lease-untried=%s dirs-kept-live=%s roots-unrecorded=%s unreadable-processes=%s processes-in-doubt=%s fuse-processes=%s families=%s seconds=%s%s%s\n' \
    "$label" "${base:-none}" "${root:-none}" "$TH_ROOT_STATE" \
    "$root_verb" "${TH_COUNT[root-removed]:-0}" "${TH_COUNT[root-bytes]:-0}" \
    "$verb" "${TH_COUNT[shared-removed]:-0}" "${TH_COUNT[shared-bytes]:-0}" \
    "$verb" "${TH_COUNT[gone-removed]:-0}" \
    "$(( ${TH_COUNT[root-partial]:-0} + ${TH_COUNT[shared-partial]:-0} + ${TH_COUNT[gone-partial]:-0} ))" \
    "$TH_FILES_REFUSED" "$TH_FILES_UNTRIED" "$TH_DIRS_LIVE" "$TH_UNRECORDED" "$TH_UNREADABLE" "$TH_DOUBTFUL" "$TH_FUSE" "${#TH_FAMILIES[@]}" "$seconds" \
    "$([ "$TH_STOPPED" -eq 0 ] || echo ' stopped at budget')" "$([ -z "$TH_LIST" ] || echo " list=$TH_LIST")"
  return 0
}

# The wrappers' entry point: AFTER verification::lock. Reads VERIFICATION_TMP_SWEEP.
th::step() { # checkout [list-file]
  local mode="${VERIFICATION_TMP_SWEEP:-real}"
  case "$mode" in
    real|dry-run) ;;
    *) echo "temp-hygiene: VERIFICATION_TMP_SWEEP='$mode' is neither real nor dry-run; the shared sweep runs as dry-run" >&2
       mode=dry-run ;;
  esac
  th::run "$1" real "$mode" "$mode" "${2:-}" || th::_warn "the step failed; the run continues"
  return 0
}

th::_main() {
  local checkout
  checkout="$(cd "$TH_LIB_DIR/../.." && pwd -P)" || exit 2
  case "${1:-}" in
    --print-root) [ "$#" -eq 1 ] || { echo 'usage: temp-hygiene.sh --dry-run | --print-root' >&2; exit 2; }
      th::print_root "$checkout" ;;
    --dry-run) [ "$#" -eq 1 ] || { echo 'usage: temp-hygiene.sh --dry-run | --print-root' >&2; exit 2; }
      local list; list="$(mktemp "${TMPDIR:-/tmp}/temp-hygiene-list.XXXXXX")" || exit 2
      th::run "$checkout" dry-run dry-run manual-dry-run "$list"
      cat -- "$list"; rm -f -- "$list" ;;
    *) echo 'usage: temp-hygiene.sh --dry-run | --print-root  (there is no manual real run: the real step runs only inside gate.sh/pregate.sh, after the lock)' >&2
      exit 2 ;;
  esac
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  set -u
  th::_main "$@"; exit $?
fi
