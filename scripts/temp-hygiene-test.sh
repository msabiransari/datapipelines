#!/usr/bin/env bash
# temp-hygiene-test.sh — the self-test of scripts/lib/temp-hygiene.sh (#983, DEVELOPMENT.md §9.7).
# Run by `scripts/pregate.sh --self-test`, and so by gate.sh's pregate self-test stage.
#
# Fixture temp directories only: every pass of the library gets TMPDIR = a fixture directory
# (the temp base), so nothing outside $T is ever a candidate. The self-test fails when any
# summary line it captured names a base outside its fixtures, and it prints the number of
# cases it ran. Seams are the library's own shell functions (and, in case 47 only, its
# TH_LIB_DIR shell variable, so the real helper runs through a wrapper that reports one mount's
# type), replaced inside the pass's subshell after sourcing; no environment variable switches
# the lease or the scan off.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
LIB="$ROOT/scripts/lib/temp-hygiene.sh"
HELPER="$ROOT/scripts/lib/temp-hygiene-helper.py"
unset VERIFICATION_TMP_SWEEP VERIFICATION_TMP_SWEEP_AGE_S VERIFICATION_TMP_BUDGET_S
T="$(mktemp -d "${TMPDIR:-/tmp}/temp-hygiene-test.XXXXXX")" || exit 2
T="$(cd "$T" && pwd -P)"
HOLDERS=()
cleanup() {
  local pid
  for pid in "${HOLDERS[@]}"; do kill "$pid" 2>/dev/null; done
  for pid in "${HOLDERS[@]}"; do wait "$pid" 2>/dev/null; done
  chmod -R u+rwx "$T" 2>/dev/null
  rm -rf "$T"
}
trap cleanup EXIT

# shellcheck source=lib/temp-hygiene.sh
source "$LIB"
UID_NOW="$(id -u)"
NOW="$(printf '%(%s)T' -1)"
OLD=$((NOW - 7200))
CASES=0; SKIPS=0; FAILS=0; PASSN=0; CASE=

fail() { echo "  FAIL case $CASE: $*" >&2; FAILS=$((FAILS + 1)); }
skip() { echo "  skip case $CASE: $*"; SKIPS=$((SKIPS + 1)); }
begin() { # begin <case> <title>
  CASE="$1"; CASES=$((CASES + 1)); TITLE="$2"; CASE_FAILS="$FAILS"
  FX="$T/case$1"; BASE="$FX/tmp"; CO="$FX/checkout"
  mkdir -p "$BASE" "$CO" "$FX/outside"
}
explain_case() {
  local record
  for record in "$FX"/doubt.* "$FX/own-refs"; do
    [ -f "$record" ] || continue
    while IFS= read -r line; do printf '  explain case %s: %s\n' "$CASE" "$line" >&2; done < "$record"
  done
}
finish() {
  [ "$FAILS" -eq "$CASE_FAILS" ] || explain_case
  stop_holders
  if [ "$FAILS" -eq "$CASE_FAILS" ]; then echo "  ok   case $CASE: $TITLE"; fi
}
old() { touch -h -d "@$OLD" -- "$@"; }
exists() { [ -e "$1" ] || [ -L "$1" ] || fail "${2:-$1} is gone"; }
gone() { if [ -e "$1" ] || [ -L "$1" ]; then fail "${2:-$1} is still there"; fi; }
field() { sed -n "s/^temp-hygiene:.* $1=\([^ ]*\).*/\1/p" "$OUT"; }
expect() { # expect <field> <value>
  local got; got="$(field "$1")"
  [ "$got" = "$2" ] || fail "summary $1=$got, expected $2: $(grep '^temp-hygiene: mode=' "$OUT")"
}
mkroot() { # mkroot <checkout> → prints the root; parent and root 0700, record beside it
  local root parent
  root="$(TMPDIR="$BASE" th::print_root "$1")"
  parent="${root%/*}"
  mkdir -p "$parent" && chmod 700 "$parent"
  mkdir -p "$root" && chmod 700 "$root"
  printf '%s\n' "$(cd "$1" && pwd -P)" > "$root.checkout"
  printf '%s\n' "$root"
}

# A process outside the case whose reference is in doubt keeps every candidate, by design (#983
# round 3, D2). The self-test runs beside other work on the same machine, so a pass leaves such
# a process out — named in $T/foreign-doubt and counted on the result line — and case 37 proves
# that a holder of the case in doubt keeps everything. A process outside the case with a reference
# on FUSE (owner ruling 2026-11-15) is left out of fuse-processes= the same way, named in
# $T/foreign-fuse, so case 47 can count its own holder. own_process: a holder of the case or its child.
own_process() { # own_process <pid>
  local pid parent
  parent="$(awk '/^PPid:/ {print $2}' "/proc/$1/status" 2>/dev/null)"
  for pid in "${CASE_HOLDERS[@]}"; do [ "$1" = "$pid" ] || [ "$parent" = "$pid" ] && return 0; done
  return 1
}
leave_out_foreign_doubt() {
  eval "$(declare -f th::_proc_refs | sed '1s/th::_proc_refs/th::_proc_refs_read/')"
  th::_proc_refs() {
    local rc=0 own=0 pid parent= key value
    th::_proc_refs_read "$1" || rc=$?
    # Same holder-or-child rule as own_process; builtin reads avoid a fork for every scanned pid.
    if [ -r "/proc/$1/status" ]; then
      while read -r key value; do
        if [ "$key" = PPid: ]; then parent="$value"; break; fi
      done < "/proc/$1/status" 2>/dev/null
    fi
    for pid in "${CASE_HOLDERS[@]}"; do
      if [ "$1" = "$pid" ] || [ "$parent" = "$pid" ]; then own=1; break; fi
    done
    if [ "$own" -eq 1 ]; then
      printf 'case %s pass %s: own %s %s rc=%s refs=%s doubt=%s\n' \
        "$CASE" "$PASSN" "$1" "$TH_PROC_NAME" "$rc" "${#TH_P_REFS[@]}" "${TH_P_DOUBT:-0}" >> "$FX/own-refs"
    fi
    if [ "${TH_P_DOUBT:-0}" -gt 0 ] && [ "$own" -eq 0 ]; then
      printf 'case %s pass %s: %s %s\n' "$CASE" "$PASSN" "$1" "$TH_PROC_NAME" >> "$T/foreign-doubt"
      TH_P_REFS=(); TH_P_DOUBT=0
    fi
    if [ "${TH_P_FUSE:-0}" -gt 0 ] && [ "$own" -eq 0 ]; then
      printf 'case %s pass %s: %s %s\n' "$CASE" "$PASSN" "$1" "$TH_PROC_NAME" >> "$T/foreign-fuse"
      TH_P_FUSE=0
    fi
    return "$rc"
  }
}

# One pass of the library in a subshell: pass <label> <root-mode> <shared-mode> [seam code].
pass() {
  local label="$1" rmode="$2" smode="$3" seams="${4:-}"
  PASSN=$((PASSN + 1))
  OUT="$FX/out.$PASSN"; ERR="$FX/err.$PASSN"; LIST="$FX/list.$PASSN"
  (
    export TMPDIR="${PASS_TMPDIR:-$BASE}"
    export TH_DOUBT_LOG="$FX/doubt.$PASSN"
    # shellcheck source=lib/temp-hygiene.sh
    source "$LIB"
    leave_out_foreign_doubt
    eval "$seams"
    th::run "$CO" "$rmode" "$smode" "$label" "$LIST"
  ) > "$OUT" 2> "$ERR" 9>&- 10>&- 11>&-
  grep '^temp-hygiene: mode=' "$OUT" >> "$T/summaries"
  [ "$(grep -c '^temp-hygiene: mode=' "$OUT")" -eq 1 ] || fail "pass $label printed no single summary line: $(cat "$OUT" "$ERR")"
}
# The wrappers' entry point, with the environment the caller set: step [seam code].
step() {
  local seams="${1:-}"
  PASSN=$((PASSN + 1))
  OUT="$FX/out.$PASSN"; ERR="$FX/err.$PASSN"; LIST="$FX/list.$PASSN"
  (
    export TMPDIR="$BASE"
    # shellcheck source=lib/temp-hygiene.sh
    source "$LIB"
    eval "$seams"
    th::step "$CO" "$LIST"
    echo "wrapper-continued rc=$?"
  ) > "$OUT" 2> "$ERR" 9>&- 10>&- 11>&-
  grep '^temp-hygiene: mode=' "$OUT" >> "$T/summaries"
}

# Live processes. Each closes the wrapper descriptors and is killed at the end of its case.
CASE_HOLDERS=()
remember() { HOLDERS+=("$1"); CASE_HOLDERS+=("$1"); }
stop_holders() {
  local pid
  for pid in "${CASE_HOLDERS[@]}"; do kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null; done
  CASE_HOLDERS=()
}
wait_for() { # wait_for <test command...>: up to 5 s
  local i
  for i in $(seq 1 50); do "$@" && return 0; sleep 0.1; done
  return 1
}
fd_open() { [ -e "/proc/$1/fd/3" ]; }
cwd_is() { [ "$(readlink "/proc/$1/cwd" 2>/dev/null)" = "$2" ]; }
hold_open() { # hold_open <file>: a process named `sleep` holds it on fd 3, its path on no command line
  ( exec 3<"$1"; exec -a holder sleep 600 ) 9>&- 10>&- 11>&- &
  remember "$!"; wait_for fd_open "$!" || fail "holder of $1 did not start"
}
hold_cwd() { # hold_cwd <dir>
  ( cd "$1" && exec sleep 600 ) 9>&- 10>&- 11>&- &
  remember "$!"; wait_for cwd_is "$!" "$1" || fail "cwd holder of $1 did not start"
}
hold_mapped() { # hold_mapped <file>: mapped, descriptor closed, path only in the environment
  local ready="$FX/mapped.$RANDOM"
  P="$1" R="$ready" python3 -c '
import mmap, os, time
handle = open(os.environ["P"], "rb")
mapped = mmap.mmap(handle.fileno(), 0, prot=mmap.PROT_READ)
handle.close()
open(os.environ["R"], "w").close()
time.sleep(600)' 9>&- 10>&- 11>&- &
  remember "$!"; wait_for test -e "$ready" || fail "mapping holder of $1 did not start"
}
payload() { head -c 4096 /dev/zero > "$1"; }

# ---- 1-4: files ---------------------------------------------------------------------------
begin 1 'a listed, old, unused file is removed'
payload "$BASE/libduckdb_java1.so"; old "$BASE/libduckdb_java1.so"
pass c1 real real
gone "$BASE/libduckdb_java1.so"; expect shared-removed 1
finish

begin 2 'a listed file younger than the age is kept'
payload "$BASE/libduckdb_java2.so"
pass c2 real real
exists "$BASE/libduckdb_java2.so"; expect shared-removed 0
finish

begin 3 'a listed old file held open by a live process is kept'
payload "$BASE/libduckdb_java3.so"; old "$BASE/libduckdb_java3.so"
hold_open "$BASE/libduckdb_java3.so"
pass c3 real real
exists "$BASE/libduckdb_java3.so"; expect files-kept-lease-refused 1; expect shared-removed 0
finish

begin 4 'a listed old .so mapped (not held open) by a live process is kept'
payload "$BASE/libduckdb_java4.so"; old "$BASE/libduckdb_java4.so"
hold_mapped "$BASE/libduckdb_java4.so"
pass c4 real real
exists "$BASE/libduckdb_java4.so"; expect files-kept-lease-refused 1; expect shared-removed 0
finish

# ---- 5: a live cwd keeps the directory whole -------------------------------------------------
begin 5 'a directory that is a live cwd is kept whole; its unused sibling is cleaned'
for d in tomcat.0.5001 tomcat.0.5002; do
  mkdir -p "$BASE/$d/sub"; payload "$BASE/$d/f"; payload "$BASE/$d/sub/g"
  old "$BASE/$d/f" "$BASE/$d/sub/g" "$BASE/$d/sub" "$BASE/$d"
done
hold_cwd "$BASE/tomcat.0.5001"
pass c5 real real
exists "$BASE/tomcat.0.5001/f" 'the live directory'"'"'s file'
exists "$BASE/tomcat.0.5001/sub/g" 'the live directory'"'"'s inner file'
gone "$BASE/tomcat.0.5002"; expect dirs-kept-live 1; expect shared-removed 1
finish

# ---- 6-8: names, never-touch, symlinks --------------------------------------------------------
begin 6 'a name not on the list is kept, old or not'
for f in notlisted.so libduckdb_java.so libduckdb_javaX.so tomcat.x; do payload "$BASE/$f"; done
mkdir -p "$BASE/tomcat.0" "$BASE/junit-1" "$BASE/tomcat.0.6.extra"
old "$BASE"/*
pass c6 real real
for f in notlisted.so libduckdb_java.so libduckdb_javaX.so tomcat.x tomcat.0 junit-1 tomcat.0.6.extra; do exists "$BASE/$f"; done
expect shared-removed 0
finish

begin 7 'each never-touch name is kept even when a pattern matches it'
never=(claude-x com.google.Chrome.abc .X11-unix hsperfdata_x systemd-private-x playwright-java-1 opencode "dp-test-tmp-$((UID_NOW + 1))")
for d in "${never[@]}"; do mkdir -p "$BASE/$d"; done
payload "$BASE/.hidden"; payload "$BASE/claude-file"; payload "$BASE/zzz-any"
old "$BASE"/* "$BASE"/.[!.]*
pass c7 real real "TH_FAMILIES=('f any .*' 'd any .*')"
for d in "${never[@]}" .hidden claude-file; do exists "$BASE/$d"; done
gone "$BASE/zzz-any" 'the widened-list control'
finish

begin 8 'a symlink to a directory outside: the outside directory keeps every file'
mkdir -p "$FX/outside/a" "$FX/outside2/b"
payload "$FX/outside/a/f"; payload "$FX/outside2/b/f"; old "$FX/outside/a/f" "$FX/outside2/b/f"
ln -s "$FX/outside" "$BASE/tomcat.0.8001"
mkdir -p "$BASE/tomcat.0.8002"; ln -s "$FX/outside2" "$BASE/tomcat.0.8002/link"
old "$BASE/tomcat.0.8001" "$BASE/tomcat.0.8002/link" "$BASE/tomcat.0.8002"
pass c8 real real
exists "$FX/outside/a/f"; exists "$FX/outside2/b/f"
exists "$BASE/tomcat.0.8001" 'the top-level link (not a directory of the family)'
gone "$BASE/tomcat.0.8002" 'the directory holding only a link'
finish

# ---- 9-13: the checkout root ----------------------------------------------------------------
begin 9 'names with a space and with a newline are handled'
root="$(mkroot "$CO")"
payload "$root/a b"; payload "$root/a"$'\n'"b"; mkdir -p "$root/d"$'\n'"x"; payload "$root/d"$'\n'"x/f"
payload "$BASE/libduckdb_java9.so"$'\n'"x"; old "$BASE/libduckdb_java9.so"$'\n'"x"
pass c9 real real
gone "$root/a b"; gone "$root/a"$'\n'"b"; gone "$root/d"$'\n'"x"
exists "$BASE/libduckdb_java9.so"$'\n'"x" 'an unlisted name with a newline'
[ "$(wc -l < "$LIST")" -eq 3 ] || fail "the list has $(wc -l < "$LIST") lines for three entries: $(cat "$LIST")"
expect root-removed 3
finish

begin 10 'a file owned by another uid is kept'
if ln /etc/passwd "$BASE/libduckdb_java10.so" 2>/dev/null; then
  pass c10 real real
  exists "$BASE/libduckdb_java10.so"
else
  skip "this box cannot create a file of another uid (no root; fs.protected_hardlinks=$(cat /proc/sys/fs/protected_hardlinks 2>/dev/null) refuses a hard link to one) — the uid check is in th::_sweep_shared and Remover.entry"
fi
finish

begin 11 'the checkout root is emptied; a second checkout'"'"'s root is not'
root="$(mkroot "$CO")"; mkdir -p "$FX/other"; other="$(mkroot "$FX/other")"
for r in "$root" "$other"; do mkdir -p "$r/modules/x/test"; payload "$r/modules/x/test/f"; payload "$r/top"; done
pass c11 real real
[ -d "$root" ] || fail 'the root itself was removed'
[ -z "$(ls -A "$root")" ] || fail "the root is not empty: $(ls -A "$root")"
exists "$other/modules/x/test/f"; exists "$other/top"; exists "$other.checkout"
expect root-state emptied
finish

begin 12 'a root that is a symlink, or writable by others, is refused'
root="$(mkroot "$CO")"; rmdir "$root"
mkdir -p "$FX/outside/sentinel"; payload "$FX/outside/sentinel/f"
ln -s "$FX/outside/sentinel" "$root"
pass c12a real real
exists "$FX/outside/sentinel/f"; expect root-state refused
grep -q 'refusing the checkout root .* is a symbolic link' "$ERR" || fail "no refusal line: $(cat "$ERR")"
rm "$root"; mkdir "$root"; chmod 770 "$root"; payload "$root/f"
pass c12b real real
exists "$root/f"; expect root-state refused
grep -q 'is writable by group or others' "$ERR" || fail "no refusal line: $(cat "$ERR")"
chmod 700 "$root"
skip 'a root owned by another uid: this box cannot create one without root (same check, th::_private_dir_problem)'
finish

begin 13 'a leftover one second old in the checkout root is removed (no age in B.1)'
root="$(mkroot "$CO")"; payload "$root/fresh"; touch -d "@$((NOW - 1))" "$root/fresh"
pass c13 real real
gone "$root/fresh"
finish

# ---- 14: roots of gone checkouts --------------------------------------------------------------
begin 14 'a gone checkout'"'"'s root goes, its record after it; part-way leaves the record'
mkdir -p "$FX/gone-checkout" "$FX/live-checkout"
gone_root="$(mkroot "$FX/gone-checkout")"; live_root="$(mkroot "$FX/live-checkout")"
parent="${gone_root%/*}"
payload "$gone_root/a"; payload "$gone_root/b"; payload "$live_root/a"
rmdir "$FX/gone-checkout"
old "$gone_root/a" "$gone_root/b" "$gone_root" "$live_root/a" "$live_root"
mkdir -p "$parent/0123456789abcdef"; payload "$parent/0123456789abcdef/f"; old "$parent/0123456789abcdef"
printf '%s\n' "$FX/never-existed" > "$parent/fedcba9876543210.checkout"
mkroot "$CO" > /dev/null
# Visit the gone root first: filesystem enumeration order must not decide this proof.
pass c14a real real 'th::_remover_extra_args() { [ "$1" != gone ] || echo --stop-after 1; }
  eval "$(declare -f th::_entries | sed "1s/th::_entries/th::_real_entries/")"
  th::_entries() {
    th::_real_entries "$@" || return $?
    [ "$1" = "$parent" ] || return 0
    local entry; local first=() rest=()
    for entry in "${TH_ENTRIES[@]}"; do
      if [[ "$entry" == *$'"'"'\t'"'"'"${gone_root##*/}" ]]; then first+=("$entry"); else rest+=("$entry"); fi
    done
    TH_ENTRIES=("${first[@]}" "${rest[@]}")
  }'
exists "$gone_root" 'the part-way root'; exists "$gone_root.checkout" 'the part-way root'"'"'s record'
expect partial 1
grep -q ' stopped at budget' "$OUT" || fail "no stopped at budget: $(cat "$OUT")"
exists "$live_root/a"; exists "$live_root.checkout"
exists "$parent/0123456789abcdef/f" 'the unrecorded root'
# The part-way removal touched the root: the next pass finishes it once it is old again.
pass c14b real real "th::_now() { echo $((NOW + 7200)); }"
gone "$gone_root" 'the finished root'; gone "$gone_root.checkout" 'the finished root'"'"'s record'
gone "$parent/fedcba9876543210.checkout" 'the record whose root and checkout are gone'
exists "$live_root/a"; exists "$live_root.checkout"
exists "$parent/0123456789abcdef/f" 'the unrecorded root'
expect gone-roots-removed 1; expect roots-unrecorded 1
finish

# ---- 15-17: clock, knobs, budget ---------------------------------------------------------------
begin 15 'with the clock pinned: exactly at the age kept, one second older removed'
pinned=$((NOW + 100000))
for n in 1 2 3; do payload "$BASE/libduckdb_java15$n.so"; done
touch -d "@$((pinned - 3600))" "$BASE/libduckdb_java151.so"
touch -d "@$((pinned - 3601))" "$BASE/libduckdb_java152.so"
touch -d "@$((pinned + 100))" "$BASE/libduckdb_java153.so"
pass c15 real real "th::_now() { echo $pinned; }"
exists "$BASE/libduckdb_java151.so" 'the candidate exactly at the age'
gone "$BASE/libduckdb_java152.so" 'the candidate one second older'
exists "$BASE/libduckdb_java153.so" 'the candidate with a future mtime'
finish

begin 16 'an invalid age, budget and mode each fall back with one stderr line'
root="$(mkroot "$CO")"; payload "$root/leftover"
payload "$BASE/libduckdb_java16.so"; old "$BASE/libduckdb_java16.so"
VERIFICATION_TMP_SWEEP_AGE_S=abc VERIFICATION_TMP_BUDGET_S=0 VERIFICATION_TMP_SWEEP=bogus step
for line in "VERIFICATION_TMP_SWEEP_AGE_S='abc' is not a whole number >= 0; using the default 3600" \
            "VERIFICATION_TMP_BUDGET_S='0' is not a whole number >= 1; using the default 60" \
            "VERIFICATION_TMP_SWEEP='bogus' is neither real nor dry-run; the shared sweep runs as dry-run"; do
  [ "$(grep -cF "$line" "$ERR")" -eq 1 ] || fail "stderr line missing or repeated: $line"
done
exists "$BASE/libduckdb_java16.so" 'the shared candidate under the invalid mode'
gone "$root/leftover" 'the root leftover under the invalid mode'
expect mode dry-run; expect shared-would-remove 1
grep -q '^wrapper-continued rc=0$' "$OUT" || fail 'the step did not return 0'
finish

begin 17 'the budget ends during the sweep, and during the scan'
root="$(mkroot "$CO")"; payload "$root/leftover"
for n in 1 2 3; do payload "$BASE/libduckdb_java17$n.so"; old "$BASE/libduckdb_java17$n.so"; done
# The clock passes the budget once the sweep has judged its first entry.
step 'th::_clock() { TH_CLOCK=$((1000 + ${TH_FAKE_LATE:-0})); }
      eval "$(declare -f th::_old_enough | sed "1s/th::_old_enough/th::_real_old_enough/")"
      th::_old_enough() { TH_FAKE_LATE=100000; th::_real_old_enough "$@"; }'
for n in 1 2 3; do exists "$BASE/libduckdb_java17$n.so" "shared candidate $n after the budget"; done
grep -q ' stopped at budget' "$OUT" || fail "no stopped at budget: $(cat "$OUT")"
grep -q '^wrapper-continued rc=0$' "$OUT" || fail 'the wrapper did not continue'
gone "$root/leftover" 'the root leftover (emptied before the budget ended)'
# The clock passes the budget at the first process of the scan: nothing is removed at all.
payload "$root/leftover"
step 'th::_clock() { TH_FAKE_N=$((${TH_FAKE_N:-0} + 1)); TH_CLOCK=$((TH_FAKE_N > 1 ? 1000000 : 1000)); }'
exists "$root/leftover" 'the root leftover when the scan ran out'
for n in 1 2 3; do exists "$BASE/libduckdb_java17$n.so" "shared candidate $n when the scan ran out"; done
grep -q ' stopped at budget' "$OUT" || fail "no stopped at budget (scan): $(cat "$OUT")"
# The remover's own deadline: past it, nothing is removed.
records="$(printf '%s\0' "$BASE/libduckdb_java171.so" | python3 "$HELPER" remove --mode real --lease try --uid "$UID_NOW" --deadline 0 | tr '\0' '\n')"
[ "$(printf '%s\n' "$records" | tail -n 1)" = '#budget' ] || fail "the remover past its deadline did not stop: $records"
case "$records" in removed*|partial*) fail "the remover past its deadline removed: $records" ;; esac
exists "$BASE/libduckdb_java171.so" 'the candidate past the remover deadline'
finish

# ---- 18: the lease and the process scan's limits ----------------------------------------------
begin 18 'held or loaded files are kept whatever the process; unreadable, zombie, gone processes'
root="$(mkroot "$CO")"
mkdir -p "$BASE/tomcat.0.1801"; payload "$BASE/tomcat.0.1801/held.bin"; payload "$BASE/tomcat.0.1801/free.bin"
old "$BASE/tomcat.0.1801/held.bin" "$BASE/tomcat.0.1801/free.bin" "$BASE/tomcat.0.1801"
hold_open "$BASE/tomcat.0.1801/held.bin"
payload "$root/loaded.so"; hold_mapped "$root/loaded.so"
# The scan is made to see no process at all: only the kernel's lease answer keeps the files.
pass c18a real real 'th::_snapshot() { TH_SNAP_PIDS=(); unset TH_SNAP_RC TH_SNAP_NAME TH_SNAP_REF_INDEX
    declare -gA TH_SNAP_RC=() TH_SNAP_NAME=() TH_SNAP_REF_INDEX=(); TH_SNAP_REF_PID=(); TH_SNAP_REF_KIND=(); TH_SNAP_REF_VALUE=(); return 0; }'
expect files-kept-lease-refused 2; expect unreadable-processes 0
exists "$BASE/tomcat.0.1801/held.bin" 'the held file'; gone "$BASE/tomcat.0.1801/free.bin" 'its unheld sibling'
exists "$BASE/tomcat.0.1801" 'the directory holding a kept file'
exists "$root/loaded.so" 'the loaded file in the root'
stop_holders
# The lease made unavailable: every file is kept.
payload "$BASE/libduckdb_java1803.so"; old "$BASE/libduckdb_java1803.so"
pass c18b real real 'th::_lease_mode() { echo off; }'
exists "$BASE/libduckdb_java1803.so" 'a file with the lease unavailable'
exists "$root/loaded.so" 'a root file with the lease unavailable'
expect files-kept-lease-untried 2
# A replaced snapshot: one live process unreadable, a zombie and a gone process naming dirs.
for d in tomcat.0.1804 tomcat.0.1805 tomcat.0.1806; do mkdir -p "$BASE/$d"; old "$BASE/$d"; done
pass c18c real real "th::_snapshot() {
    TH_SNAP_PIDS=(4000000001 4000000002 4000000003); unset TH_SNAP_RC TH_SNAP_NAME TH_SNAP_REF_INDEX
    declare -gA TH_SNAP_RC=([4000000001]=2 [4000000002]=4 [4000000003]=3) TH_SNAP_NAME=([4000000001]=fake-unreadable [4000000002]=fake-zombie [4000000003]=fake-gone)
    declare -gA TH_SNAP_REF_INDEX=([4000000002]='0 ' [4000000003]='1 ')
    TH_SNAP_REF_PID=(4000000002 4000000003); TH_SNAP_REF_KIND=(c f)
    TH_SNAP_REF_VALUE=('$BASE/tomcat.0.1805' '$BASE/tomcat.0.1806/x'); return 0; }"
gone "$BASE/tomcat.0.1804" 'the empty old directory (owner ruling)'
gone "$BASE/tomcat.0.1805" 'the directory a zombie named'
gone "$BASE/tomcat.0.1806" 'the directory a gone process named'
expect unreadable-processes 1
grep -q 'could not be read and keep no directory .*first five: 4000000001 fake-unreadable' "$ERR" || fail "no unreadable stderr line: $(cat "$ERR")"
# A real zombie whose cwd was the directory holds nothing.
mkdir -p "$BASE/tomcat.0.1807"; old "$BASE/tomcat.0.1807"
ready="$FX/zombie"
D="$BASE/tomcat.0.1807" R="$ready" python3 -c '
import os, time
pid = os.fork()
if pid == 0:
    os.chdir(os.environ["D"])
    os._exit(0)
open(os.environ["R"], "w").write(str(pid))
time.sleep(600)' 9>&- 10>&- 11>&- &
remember "$!"
zombie_is() { [ -s "$ready" ] && [ "$(awk '{print $3}' "/proc/$(cat "$ready")/stat" 2>/dev/null)" = Z ]; }
if wait_for zombie_is; then
  pass c18d real real
  gone "$BASE/tomcat.0.1807" 'the directory a real zombie sat in'
else
  fail 'no zombie appeared'
fi
finish

# ---- 19: a hostile parent ----------------------------------------------------------------------
begin 19 'a parent that is a symlink or writable by others: nothing removed through it'
root="$(TMPDIR="$BASE" th::print_root "$CO")"; parent="${root%/*}"; key="${root##*/}"
mkdir -p "$FX/outside/sentinel/$key"; payload "$FX/outside/sentinel/$key/f"
ln -s "$FX/outside/sentinel" "$parent"
pass c19a real real
exists "$FX/outside/sentinel/$key/f" 'the sentinel behind a symlinked parent'
expect root-state refused
grep -q "refusing $parent: it is a symbolic link" "$ERR" || fail "no refusal line: $(cat "$ERR")"
rm "$parent"; mkdir -p "$root"; chmod 770 "$parent"; chmod 700 "$root"; payload "$root/f"
pass c19b real real
exists "$root/f" 'a file below a group-writable parent'; expect root-state refused
grep -q 'is writable by group or others' "$ERR" || fail "no refusal line: $(cat "$ERR")"
skip 'a parent owned by another uid: this box cannot create one without root (same check, th::_private_dir_problem; the Gradle side is CommonConventionsPluginTest)'
finish

# ---- 20: through the copied wrappers -----------------------------------------------------------
begin 20 'through the copied pregate.sh and gate.sh: dry-run empties the root and lists; the default removes'
# shellcheck source=pregate-results-lib.sh
source "$ROOT/scripts/pregate-results-lib.sh"
PF="$FX/pregate-checkout"
pgres::_fixture "$PF" happy > "$FX/pregate-fixture.log" 2>&1 || fail "pregate fixture: $(cat "$FX/pregate-fixture.log")"
# The gate fixture: the real gate.sh and the libraries it sources; stand-ins for Gradle, the
# nested pregate self-test, the scans and the docs audit (no JVM, no network, no recursion).
GF="$FX/gate-checkout"
mkdir -p "$GF/scripts/lib"
cp "$ROOT/scripts/gate.sh" "$GF/scripts/"
cp "$ROOT/scripts/lib/verification-lock.sh" "$ROOT/scripts/lib/gate-stages.sh" \
   "$ROOT/scripts/lib/temp-hygiene.sh" "$ROOT/scripts/lib/temp-hygiene-helper.py" "$GF/scripts/lib/"
printf 'SCAN_EXIT_OFFLINE=200\nscan_tools_classify_network() { echo offline; }\n' > "$GF/scripts/lib/scan-tools.sh"
for stub in gradlew scripts/pregate.sh scripts/vuln-scan.sh scripts/docs-audit.sh; do
  printf '#!/usr/bin/env bash\nexit 0\n' > "$GF/$stub"; chmod +x "$GF/$stub"
done
wrapper() { # wrapper <checkout> <script> [args]: the copied wrapper, its own lock cache, this case's temp base
  local co="$1"; shift
  PASSN=$((PASSN + 1)); OUT="$FX/out.$PASSN"
  ( cd "$co" && TMPDIR="$BASE" XDG_CACHE_HOME="$co/.fixture-cache" DATAPIPELINES_VERIFICATION_MEM_FLOOR_MB=0 \
      timeout 300 bash "$@" ) > "$OUT" 2>&1 9>&- 10>&- 11>&-
  grep '^temp-hygiene: mode=' "$OUT" >> "$T/summaries"
  [ "$(grep -c '^temp-hygiene: mode=' "$OUT")" -eq 1 ] || fail "$* printed no single summary line: $(tail -n 20 "$OUT")"
}
for which in pregate gate; do
  if [ "$which" = pregate ]; then co="$PF"; n=1; run=(scripts/pregate.sh HEAD); else co="$GF"; n=2; run=(scripts/gate.sh); fi
  root="$(mkroot "$co")"
  payload "$root/leftover1"; mkdir -p "$root/modules/x/test"; payload "$root/modules/x/test/f"
  payload "$BASE/libduckdb_java20${n}1.so"; old "$BASE/libduckdb_java20${n}1.so"
  VERIFICATION_TMP_SWEEP=dry-run wrapper "$co" "${run[@]}"
  gone "$root/leftover1" "$which dry-run: the root leftover"; gone "$root/modules" "$which dry-run: the root folder"
  exists "$BASE/libduckdb_java20${n}1.so" "$which dry-run: the listed old shared candidate"
  expect mode dry-run; expect shared-would-remove 1
  payload "$root/leftover2"
  payload "$BASE/libduckdb_java20${n}2.so"; old "$BASE/libduckdb_java20${n}2.so"
  ( unset VERIFICATION_TMP_SWEEP; wrapper "$co" "${run[@]}"; echo "$OUT" > "$FX/last-out" )
  OUT="$(cat "$FX/last-out")"
  gone "$root/leftover2" "$which default: the root leftover"
  gone "$BASE/libduckdb_java20${n}1.so" "$which default: shared candidate 1"
  gone "$BASE/libduckdb_java20${n}2.so" "$which default: shared candidate 2"
  expect mode real; expect shared-removed 2
done
finish

# ---- 21-23: a live command-line argument keeps its whole name (#983 round 2, C1) -------------
# Each holder has no cwd and no open handle below the base: its ONLY reference is one
# command-line argument. Its entry and an unused sibling get the same closed files (directly
# inside and one level below); the sibling going in the same pass is the control that the
# sweep ran and that the closed files would have been leased away.
cmdline_has() { # cmdline_has <pid> <argument>: the exact argument, NUL-safe
  local argument
  while IFS= read -r -d '' argument; do [ "$argument" = "$2" ] && return 0; done < "/proc/$1/cmdline" 2>/dev/null
  return 1
}
hold_arg() { # hold_arg <argument> [cwd, default /]: a sleeping process whose command line carries it
  ( cd "${2:-/}" && exec python3 -c 'import time; time.sleep(600)' "$1" ) < /dev/null > /dev/null 2>&1 9>&- 10>&- 11>&- &
  remember "$!"; wait_for cmdline_has "$!" "$1" || fail "argument holder of $1 did not start"
  HOLDER="$!"
}
fill_entry() { # fill_entry <dir>: closed files directly inside and one level below
  mkdir -p "$1/nested"; payload "$1/closed-file"; payload "$1/nested/closed-file"
}
argument_case() { # argument_case <entry name> <sibling name> <argument with @ for the root>
  local root entry sibling argument
  root="$(mkroot "$CO")"; entry="$root/$1"; sibling="$root/$2"
  fill_entry "$entry"; fill_entry "$sibling"
  argument="${3//@/$root}"
  hold_arg "$argument"
  pass "c$CASE" real real
  [ -n "${HOLDER:-}" ] && kill -0 "$HOLDER" 2>/dev/null || fail 'the holder did not stay alive through the pass'
  exists "$entry/closed-file" 'the closed file directly inside the referenced entry'
  exists "$entry/nested/closed-file" 'the closed file one level below the referenced entry'
  gone "$sibling" 'the unused sibling with the same contents (the control)'
  expect dirs-kept-live 1; expect root-removed 1
}

begin 21 'a plain absolute argument with a space keeps its entry whole'
argument_case 'a b' 'a b2' '@/a b/nested'
finish

begin 22 'an option value with a space keeps its entry whole'
argument_case 'opt dir' 'opt dir2' '--work-dir=@/opt dir'
finish

begin 23 'an argument naming an entry with a newline (and a comma) keeps it whole'
argument_case $'nl\nx,y' $'nl\nx,z' $'@/nl\nx,y/nested'
finish

# ---- 24-25: the one budget covers kept entries too (#983 round 2, C2) -------------------------
# Every file is kept (lease off: untried), so no removal ever happens; the deadline is passed
# to the helper through the seam (argparse keeps the last --deadline), never by a sleep.
kept_root() { # kept_root <count> → prints the root, holding <count> files (kept with the lease off)
  local root n
  root="$(mkroot "$CO")"
  for n in $(seq 1 "$1"); do payload "$root/kept$n"; done
  printf '%s\n' "$root"
}
list_lines() { grep -c "^$1 " "$LIST" || true; } # list_lines <verdict>

begin 24 'a deadline already passed: no entry is visited, the budget is signalled'
dir="$FX/kept-dir"; mkdir -p "$dir"; for n in 1 2 3; do payload "$dir/f$n"; done
records="$(printf '%s\0' "$dir" | python3 "$HELPER" remove --mode real --lease off --uid "$UID_NOW" --deadline 0 | tr '\0' '\n')"
[ "$records" = '#budget' ] || fail "the helper past its deadline visited or did not signal: $(printf '%q' "$records")"
root="$(kept_root 4)"
pass c24 real real 'th::_lease_mode() { echo off; }; th::_remover_extra_args() { echo --deadline 0; }'
grep -q ' stopped at budget' "$OUT" || fail "no stopped at budget: $(cat "$OUT")"
[ "$(list_lines kept)" -eq 0 ] || fail "entries were visited past the deadline: $(cat "$LIST")"
for n in 1 2 3 4; do exists "$root/kept$n"; done
finish

begin 25 'the budget ends part-way through kept entries: the later ones are not visited'
dir="$FX/kept-dir"; mkdir -p "$dir"; for n in 1 2 3 4 5 6; do payload "$dir/f$n"; done
# Visit 1 is the directory, visits 2 and 3 two of its files; the fourth visit meets the limit.
records="$(printf '%s\0' "$dir" | python3 "$HELPER" remove --mode real --lease off --uid "$UID_NOW" --deadline 99999999999 --stop-after-visits 3 | tr '\0' '\n')"
[ "$records" = "kept"$'\t'"0"$'\t'"0"$'\t'"0"$'\t'"2"$'\t'"0"$'\t'"$dir"$'\n#budget' ] \
  || fail "the helper visited past the limit or did not signal (expected 2 of 6 files visited): $(printf '%q' "$records")"
root="$(kept_root 5)"
pass c25 real real 'th::_lease_mode() { echo off; }; th::_remover_extra_args() { echo --stop-after-visits 2; }'
grep -q ' stopped at budget' "$OUT" || fail "no stopped at budget: $(cat "$OUT")"
[ "$(list_lines kept)" -eq 2 ] || fail "expected 2 visited entries, 3 not visited: $(cat "$LIST")"
expect files-kept-lease-untried 2
for n in 1 2 3 4 5; do exists "$root/kept$n"; done
finish

# ---- 26-39: a live reference keeps by what it RESOLVES to, never by its spelling (#983 round 3) ----
# Each holder's ONLY reference to its entry is one command-line argument (its cwd is outside the
# entry, it holds no handle there). Before the pass, the argument is shown to resolve into the
# entry (a red is never a setup error); after it, the closed files directly inside and one level
# below are still there, the holder is alive, and an unused sibling with the same contents went
# in the same pass. S1-S9 are the round 3 brief's rows; 35-39 are the classes found while working.
into() { # into <path> <entry> [cwd]: the path, resolved by the kernel from cwd, is the entry or below it
  local resolved
  resolved="$(cd "${3:-/}" && realpath -e -- "$1" 2>/dev/null)" || resolved=
  case "$resolved" in
    "$2"|"$2"/*) return 0 ;;
    *) fail "setup: $1 resolves to '$resolved', not into $2"; return 1 ;;
  esac
}
held_whole() { # held_whole <entry>: both closed sentinels survived and the holder lives
  [ -n "${HOLDER:-}" ] && kill -0 "$HOLDER" 2>/dev/null || fail "the holder of $1 did not stay alive through the pass"
  exists "$1/closed-file" "the closed file directly inside $1"
  exists "$1/nested/closed-file" "the closed file one level below $1"
}
# resolve_case <entry> <path> [option prefix] [cwd]: a live entry, an unused sibling, one holder.
resolve_case() {
  local entry="$1" path="$2" prefix="${3:-}" cwd="${4:-/}"
  fill_entry "$entry"; fill_entry "$entry-unused"
  into "$path" "$entry" "$cwd" || return 1
  hold_arg "$prefix$path" "$cwd"
  pass "c$CASE" real real
  held_whole "$entry"
  gone "$entry-unused" 'the unused sibling with the same contents (the control)'
}

begin 26 'S1: parent traversal (<root>/hop/../live dir/nested) keeps the entry it resolves to'
root="$(mkroot "$CO")"; mkdir -p "$root/hop"
resolve_case "$root/live dir" "$root/hop/../live dir/nested"
expect dirs-kept-live 1
gone "$root/hop" 'hop: the path only passes through it (S9 rule)'
finish

begin 27 'S2: the configured temp base reached through a symlink, plain argument'
ln -s "$BASE" "$FX/tmp-alias"
root="$(mkroot "$CO")"; alias_root="$FX/tmp-alias/${root#"$BASE"/}"
PASS_TMPDIR="$FX/tmp-alias" resolve_case "$root/live" "$alias_root/live/nested"
expect base "$BASE"; expect dirs-kept-live 1
finish

begin 28 'S3: S1 and S2 as the value of an option (--dir=<path>)'
ln -s "$BASE" "$FX/tmp-alias"
root="$(mkroot "$CO")"; mkdir -p "$root/hop"; alias_root="$FX/tmp-alias/${root#"$BASE"/}"
fill_entry "$root/opt-dotdot"; fill_entry "$root/opt-alias"; fill_entry "$root/opt-unused"
into "$root/hop/../opt-dotdot/nested" "$root/opt-dotdot" && into "$alias_root/opt-alias/nested" "$root/opt-alias" || true
hold_arg "--dir=$root/hop/../opt-dotdot/nested"; first="$HOLDER"
hold_arg "--dir=$alias_root/opt-alias/nested"
PASS_TMPDIR="$FX/tmp-alias" pass c28 real real
held_whole "$root/opt-alias"; HOLDER="$first"; held_whole "$root/opt-dotdot"
gone "$root/opt-unused" 'the unused sibling (the control)'
expect dirs-kept-live 2
finish

begin 29 'S4: "." segments, doubled slashes and a trailing slash'
root="$(mkroot "$CO")"
resolve_case "$root/live" "$root//live/./nested/"
expect dirs-kept-live 1
finish

begin 30 'S5: a symlink outside the entry, elsewhere in the path, points into it'
root="$(mkroot "$CO")"; mkdir -p "$root/live"; ln -s "$root/live" "$FX/outside/into-live"
resolve_case "$root/live" "$FX/outside/into-live/nested"
expect dirs-kept-live 1
finish

begin 31 'S6: a reference deeper than three levels below the entry'
root="$(mkroot "$CO")"; mkdir -p "$root/live/nested/a/b/c/d"
resolve_case "$root/live" "$root/live/nested/a/b/c/d"
expect dirs-kept-live 1
finish

begin 32 'S7: a relative argument that resolves into the entry from the holder'"'"'s cwd (outside it)'
root="$(mkroot "$CO")"
resolve_case "$root/live" "${root#"$FX"/}/live/nested" '' "$FX"
expect dirs-kept-live 1
finish

begin 33 'S8 (control): the canonical absolute argument'
root="$(mkroot "$CO")"
resolve_case "$root/live" "$root/live/nested"
expect dirs-kept-live 1
finish

begin 34 'S9: an argument that leaves the entry through ".." keeps only where it lands'
root="$(mkroot "$CO")"; fill_entry "$root/live"; fill_entry "$root/other"
into "$root/live/../other" "$root/other" || true
hold_arg "$root/live/../other"
pass c34 real real
held_whole "$root/other"
gone "$root/live" 'live: the reference only passes through it and leaves by ".."'
expect dirs-kept-live 1; expect root-removed 1
finish

begin 35 'another mount namespace: the holder names the entry through its own bind mount'
root="$(mkroot "$CO")"; fill_entry "$root/live"; fill_entry "$root/live-unused"
# The bind source travels on a descriptor (bwrap --args), so no process carries the entry's path as text.
printf -- '--die-with-parent\0--dev-bind\0/\0/\0--tmpfs\0/mnt\0--bind\0%s\0/mnt/view\0--chdir\0/\0' "$root/live" > "$FX/outside/bwrap-args"
if ! command -v bwrap >/dev/null 2>&1 || ! bwrap --args 3 true 3< "$FX/outside/bwrap-args" 2>/dev/null; then
  skip 'bwrap is missing or cannot make a mount namespace here: no other way to start one without root'
else
  inside="$(bwrap --args 3 stat -c %d:%i /mnt/view/nested 3< "$FX/outside/bwrap-args" 2>/dev/null)"
  [ "$inside" = "$(stat -c %d:%i "$root/live/nested")" ] || fail "setup: /mnt/view/nested in the namespace is '$inside', not $root/live/nested"
  ( cd / && exec bwrap --args 3 python3 -c 'import time; time.sleep(600)' /mnt/view/nested 3< "$FX/outside/bwrap-args" ) < /dev/null > /dev/null 2>&1 9>&- 10>&- 11>&- &
  remember "$!"; launcher="$!"
  child_with_arg() { local pid; for pid in $(pgrep -P "$launcher" 2>/dev/null); do cmdline_has "$pid" /mnt/view/nested && { HOLDER="$pid"; return 0; }; done; return 1; }
  HOLDER=; wait_for child_with_arg || fail 'the namespaced holder did not start'
  [ -z "$HOLDER" ] || remember "$HOLDER"
  if grep -qaF -- "$root/live" /proc/"$launcher"/cmdline /proc/"$HOLDER"/cmdline 2>/dev/null; then fail 'setup: a holder carries the entry path as text'; fi
  pass c35 real real
  held_whole "$root/live"
  gone "$root/live-unused" 'the unused sibling (the control)'
  expect dirs-kept-live 1
fi
finish

begin 36 'a path through /proc/<pid>/cwd of another process'
root="$(mkroot "$CO")"; hold_cwd "$root"; through="/proc/$!/cwd"
resolve_case "$root/live" "$through/live/nested"
expect dirs-kept-live 1
finish

begin 37 'doubt keeps: a reference that cannot be resolved for certain keeps every candidate'
root="$(mkroot "$CO")"; fill_entry "$root/live"; fill_entry "$root/live-unused"
# (a) A link to the entry inside a directory this uid cannot search: the lookup is refused.
mkdir -p "$FX/outside/locked"; ln -s "$root/live" "$FX/outside/locked/link"
[ "$(readlink "$FX/outside/locked/link")" = "$root/live" ] && [ -d "$root/live/nested" ] || fail 'setup: the locked link does not name the entry'
chmod 000 "$FX/outside/locked"
hold_arg "$FX/outside/locked/link/nested"
pass c37a real real
held_whole "$root/live"
exists "$root/live-unused/closed-file" 'the unused sibling: doubt keeps every candidate'
expect processes-in-doubt 1; expect root-removed 0
grep -q "1 live process(es) have a reference that could not be resolved for certain.*first five: $HOLDER python3" "$ERR" || fail "no doubt stderr line naming the holder: $(cat "$ERR")"
grep -q 'origin=chain why=errno-EACCES' "$FX/doubt.$PASSN" || fail 'no errno-EACCES line for chain'
stop_holders; chmod 700 "$FX/outside/locked"
# (b) A link loop: more links than the kernel follows in one lookup.
ln -s loop "$FX/outside/loop"
hold_arg "--token=DPK-MARKER-1098:$FX/outside/loop/nested"
pass c37b real real
exists "$root/live/closed-file" 'the entry under a looping reference'
exists "$root/live-unused/closed-file" 'the unused sibling under a looping reference'
expect processes-in-doubt 1
grep -q 'origin=lookup why=too-many-links' "$FX/doubt.$PASSN" || fail 'no too-many-links line for lookup'
if grep -q 'DPK-MARKER-1098' "$FX/doubt.$PASSN"; then fail 'argument marker leaked into doubt log'; fi
stop_holders
# (c) A listed name that a fresh read still lists but cannot be opened: vanished while read.
mkdir -p "$FX/outside/churn/kept"
TH_DOUBT_LOG="$FX/doubt.listed" HELPER="$HELPER" C="$root/live" D="$FX/outside/churn" python3 -I -B -c '
import importlib.util, os, sys, time
spec = importlib.util.spec_from_file_location("helper", os.environ["HELPER"])
helper = importlib.util.module_from_spec(spec); spec.loader.exec_module(helper)
candidates = helper.candidate_map([os.path.dirname(os.environ["C"])])
class Listed(helper.Resolver):
    def listing(self, fd):
        return frozenset(["gone", "kept"])  # every read lists "gone"; no open finds it
resolver = Listed(candidates, time.time() + 60)
start = os.open("/", helper.PATH_DIR)
doubted = resolver.walk(start, os.environ["D"] + "/gone/x", 1, False)
resolver = helper.Resolver(candidates, time.time() + 60)  # the real reads: "gone" is in neither
nothing = resolver.walk(start, os.environ["D"] + "/gone/x", 1, False)
ok = doubted == resolver.everything and resolver.doubts == 0 and not nothing and resolver.everything
print("vanished: doubt=%s nothing=%s" % (doubted == resolver.everything, not nothing))
sys.exit(0 if ok else 1)' > "$FX/vanished.out" 2>&1 || fail "the vanished-while-read rule: $(cat "$FX/vanished.out")"
grep -q 'origin=lookup why=listed-not-openable' "$FX/doubt.listed" || fail 'no listed-not-openable line for lookup'
# (d-h) Origins, the sink boundary, and cause events versus affected references. No extra sweep.
HELPER="$HELPER" FX="$FX" C="$root/live" python3 -I -B - <<'PY' > "$FX/diagnostic-checks.out" 2>&1 || fail "doubt diagnostic checks: $(cat "$FX/diagnostic-checks.out")"
import ast
import errno
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import time

spec = importlib.util.spec_from_file_location("helper", os.environ["HELPER"])
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)
fx = Path(os.environ["FX"])
candidate = os.environ["C"]

def check(ok, row):
    if not ok:
        raise AssertionError(row)

def contents(path):
    return path.read_text() if path.exists() else ""

def log_at(name):
    os.environ["TH_DOUBT_LOG"] = str(fx / ("doubt." + name))
    helper._log = helper.DoubtLog()
    return Path(os.environ["TH_DOUBT_LOG"])

# Every raised and returned origin requires a token, including conditional errno expressions.
def token_calls(tree):
    calls = [n for n in ast.walk(tree) if isinstance(n, ast.Call) and
             ((isinstance(n.func, ast.Name) and n.func.id == "Doubt") or
              (isinstance(n.func, ast.Attribute) and n.func.attr == "doubt"))]
    check(len(calls) >= 18, "token-call floor: fewer than 18 calls")
    for call in calls:
        minimum = 1 if isinstance(call.func, ast.Name) else 2
        check(len(call.args) >= minimum, "token-less call at line %s" % call.lineno)

token_calls(ast.parse(Path(os.environ["HELPER"]).read_text()))

class NoTable(helper.Resolver):
    def find_mount(self, mount_id, fresh=False):
        return None

log = log_at("no-table")
r = NoTable(helper.candidate_map([os.path.dirname(candidate)]), time.time() + 60)
fd = os.open(candidate, helper.PATH_DIR)
try:
    check(r.kept_by(fd) == r.everything and r.doubts == 1, "no-table returned doubt")
finally:
    os.close(fd)
check("origin=above_root why=no-table-line" in contents(log), "no no-table-line line")

# Both non-call origins: linked versus unlinked files, and an unknown candidate path.
log = log_at("direct")
r = helper.Resolver.__new__(helper.Resolver)
r.doubts = 0
r.superblock = lambda fd: (False, None, False)
r.scope = lambda dev: frozenset([candidate])
file = fx / "direct-file"
file.write_text("fixture")
fd = os.open(file, os.O_RDONLY)
try:
    check(r.kept_by_file(fd, -1) == {candidate} and r.doubts == 1, "linked file judgment")
    os.unlink(file)
    check(not r.kept_by_file(fd, -1) and r.doubts == 1, "unlinked file control")
finally:
    os.close(fd)
check("origin=kept_by_file why=not-own-data" in contents(log), "no direct file-data line")
r.fs_paths = {}
check(r.containing(frozenset([candidate]), "/", True) == (frozenset([candidate]), True), "unknown candidate judgment")
check("origin=containing why=candidate-path-unknown" in contents(log), "no containing-path line")

# Two sites with the same errno stay separate, repeated fresh events keep one detail line.
log = log_at("counts")
for site in ("site-one", "site-two"):
    try:
        raise helper.Doubt("errno-EACCES", origin="chain", site=site)
    except helper.Doubt:
        pass
helper._log.total(2)
text = contents(log)
check("site-one/errno-EACCES=1" in text and "site-two/errno-EACCES=1" in text, "distinct errno sites")
helper._log.event("chain", "errno-EACCES", "site-one")
helper._log.total(3)
text = contents(log)
check(text.count("site=site-one") == 1 and "site-one/errno-EACCES=2" in text, "repeated fresh event")

# Real chain caching: one new mount-root cause, then nine reused judgments.
log = log_at("cache")
r = NoTable(helper.candidate_map([os.path.dirname(candidate)]), time.time() + 60)
fd = os.open(candidate, helper.PATH_DIR)
try:
    for _ in range(10):
        check(r.kept_by(fd) == r.everything, "cached kept set")
finally:
    os.close(fd)
helper._log.total(r.doubts)
text = contents(log)
check(r.doubts == 10 and "affected-references=10" in text and
      "above-root-table/no-table-line=1" in text.split(), "cache hits are not cause events")

# Two independent causes combined into the single reference counted by kept_by_file.
log = log_at("combined")
r = helper.Resolver.__new__(helper.Resolver)
r.doubts = 0
r.scope = lambda dev: frozenset([candidate])
r.judge_directory = lambda fd: r.doubt(None, "parent", origin="chain", site="combined-parent")
r.superblock = lambda fd: (False, None, False)
file.write_text("fixture")
fd = os.open(file, os.O_RDONLY)
try:
    check(r.kept_by_file(fd, 0) == {candidate} and r.doubts == 1, "combined kept and affected")
finally:
    os.close(fd)
helper._log.total(r.doubts)
text = contents(log)
check("affected-references=1" in text and "combined-parent/parent=1" in text and
      "file-data/not-own-data=1" in text, "combined causes")

# Each sink runs in a fresh interpreter, under a deadline, with the same deterministic resolution.
program = r"""
import importlib.util, os, sys
spec = importlib.util.spec_from_file_location("helper", os.environ["HELPER"])
h = importlib.util.module_from_spec(spec); spec.loader.exec_module(h)
mode = sys.argv[1]
before = len(os.listdir("/proc/self/fd"))
if mode == "unset":
    def no_open(*args, **kwargs):
        raise AssertionError("disabled diagnostic opened a file")
    h.os.open = no_open
if mode == "fstat-error":
    def fstat_error(*args):
        raise OSError(5, "injected fstat failure")
    h.os.fstat = fstat_error
if mode in ("write-error", "special"):
    def write_spy(*args):
        if mode == "special":
            raise AssertionError("special file written")
        raise OSError(5, "injected write failure")
    h.os.write = write_spy
r = h.Resolver.__new__(h.Resolver)
r.doubts = 0
r.scope = lambda dev: frozenset(["candidate"])
kept = r.counted(r.doubt(None, "no-table-line", origin="above_root", site="sink-test"))
h._log.total(r.doubts)
if mode in ("unset", "symlink", "fifo", "missing", "fstat-error", "special"):
    assert len(os.listdir("/proc/self/fd")) == before, "rejected sink leaked a descriptor"
print(sorted(kept), r.doubts)
"""
sentinel = fx / "sentinel"
sentinel.write_text("unchanged")
link = fx / "sink-link"
link.symlink_to(sentinel)
fifo = fx / "sink-fifo"
os.mkfifo(fifo)
regular = fx / "sink-regular"
paths = {"unset": None, "symlink": link, "fifo": fifo, "missing": fx / "missing" / "log",
         "write-error": fx / "sink-write-error", "fstat-error": fx / "sink-fstat-error",
         "special": Path("/dev/null"), "regular": regular}
results = {}
for mode, path in paths.items():
    env = dict(os.environ)
    env.pop("TH_DOUBT_LOG", None)
    if path is not None:
        env["TH_DOUBT_LOG"] = str(path)
    try:
        result = subprocess.run([sys.executable, "-I", "-B", "-c", program, mode], env=env,
                                capture_output=True, timeout=2)
    except subprocess.TimeoutExpired:
        raise AssertionError("sink %s exceeded two-second deadline" % mode)
    check(result.returncode == 0, "sink %s exit: %r" % (mode, result.stderr))
    results[mode] = (result.returncode, result.stdout, result.stderr)
    check(results[mode] == results["unset"], "sink %s changed resolver stdout/kept/count/exit" % mode)
check(sentinel.read_text() == "unchanged", "symlink sentinel changed")
check(not (fx / "missing").exists(), "missing parent created")
check("no-table-line" in regular.read_text() and regular.stat().st_mode & 0o777 == 0o600,
      "regular-file sink success and mode")
check(not (fx / "sink-unset").exists(), "unset sink created a file")
print("diagnostic checks: origins, token floor, direct judgments, sinks, event counts passed")
PY

finish

begin 38 'a name that only starts with dots is not "..": the path may end at the entry before it'
# Only an exact ".." component leaves (S9). "<root>/live/.absent" and "<root>/dots/..." may be a
# path that ends at "<root>/live/" or "<root>/dots/" followed by other text: both entries are kept.
root="$(mkroot "$CO")"; fill_entry "$root/live"; fill_entry "$root/dots"; fill_entry "$root/live-unused"
[ ! -e "$root/live/.absent" ] && [ ! -e "$root/dots/..." ] || fail 'setup: the dot-led names must not exist'
into "$root/live/" "$root/live"; into "$root/dots/" "$root/dots"
hold_arg "$root/live/.absent"; first="$HOLDER"
hold_arg "$root/dots/..."
pass c38 real real
held_whole "$root/dots"; HOLDER="$first"; held_whole "$root/live"
gone "$root/live-unused" 'the unused sibling with the same contents (the control)'
expect dirs-kept-live 2; expect root-removed 1
finish

begin 39 'argument starts the code reads differently: an unlistable cwd, ".." at the root, /proc/self'
# (a) The holder's cwd can be searched but not listed: a relative path may start anywhere in the
#     argument (here after "--dir="), not only where a name of the cwd appears.
# (b) ".." at the holder's root stays at the root, as the kernel resolves it.
# (c) "/proc/self" in the holder's argument names the holder (its cwd), not the scanning process.
root="$(mkroot "$CO")"
for entry in blind rooted self; do fill_entry "$root/$entry"; done; fill_entry "$root/unused"
mkdir -p "$FX/blind" "$FX/selfcwd"
ln -s "$root/blind" "$FX/blind/in"; ln -s "$root/self" "$FX/selfcwd/in"
chmod 311 "$FX/blind"
into "in/nested" "$root/blind" "$FX/blind"
into "/../..$root/rooted/nested" "$root/rooted"
into "/proc/self/cwd/in/nested" "$root/self" "$FX/selfcwd"
hold_arg "--dir=in/nested" "$FX/blind"; held_blind="$HOLDER"
hold_arg "/../..$root/rooted/nested"; held_rooted="$HOLDER"
hold_arg "/proc/self/cwd/in/nested" "$FX/selfcwd"
pass c39 real real
held_whole "$root/self"; HOLDER="$held_rooted"; held_whole "$root/rooted"; HOLDER="$held_blind"; held_whole "$root/blind"
gone "$root/unused" 'the unused sibling with the same contents (the control)'
expect dirs-kept-live 3; expect root-removed 1; expect processes-in-doubt 0
stop_holders; chmod 700 "$FX/blind"
finish

# ---- 40-46: the mount topology: absence is proved in the candidate's own filesystem tree (#983 round 4) ----
# T1-T5 of the round 4 brief, beside case 35 (T6, the candidate itself bound) and in its style: a
# holder in its own mount namespace (bwrap, unprivileged) whose ONLY reference reaches the entry
# through a mount of one of the entry's DESCENDANTS (or a root inside it). Before the pass: the
# holder is started and readable, the reference names the intended directory (device and inode,
# through /proc/<holder>/root), and neither the holder nor its launcher has the entry's path in
# its argv or a cwd or handle inside the entry other than the reference under test. After it:
# closed files directly inside, one level below and two levels below are there, the holder lives,
# the unused sibling with the same contents went in the same pass, and the holder was FOUND (no
# doubt). 45 is T7 and T8: a namespaced holder keeps only what it reaches, another whose mounts
# and references are all outside keeps nothing. 46: a stacked filesystem (overlay) over a
# directory inside the entry is doubt, and doubt keeps.
fill_deep() { # fill_deep <dir>: closed files directly inside, one and two levels below
  mkdir -p "$1/nested/deeper"; payload "$1/closed-file"; payload "$1/nested/closed-file"; payload "$1/nested/deeper/closed-file"
}
held_deep() { # held_deep <entry>
  [ -n "${HOLDER:-}" ] && kill -0 "$HOLDER" 2>/dev/null || fail "the holder of $1 did not stay alive through the pass"
  exists "$1/closed-file" "the closed file directly inside $1"
  exists "$1/nested/closed-file" "the closed file one level below $1"
  exists "$1/nested/deeper/closed-file" "the closed file two levels below $1"
}
ns_args() { # ns_args <bwrap argument...>: the NUL-separated argument file (no process carries a path of it as text)
  printf '%s\0' "$@" > "$FX/outside/bwrap-args"
}
ns_ready_code='import os, sys, time
for path in os.environ.get("HOLD_FILE", "").split(":"):
    if path: held = open(path, "rb")
if os.environ.get("HOLD_DIR"): held_dir = os.open(os.environ["HOLD_DIR"], os.O_RDONLY | os.O_DIRECTORY)
open(os.environ["READY"], "w").write(str(os.getpid()))
time.sleep(600)'
ns_hold() { # ns_hold <ready path inside the namespace> [argument]: python3 in the namespace of $FX/outside/bwrap-args
  local ready="$FX/outside/ready"
  rm -f "$ready"
  ( cd / && READY="$1" exec bwrap --args 3 python3 -c "$ns_ready_code" ${2+"$2"} 3< "$FX/outside/bwrap-args" ) \
    < /dev/null > /dev/null 2> "$FX/outside/bwrap.err" 9>&- 10>&- 11>&- &
  LAUNCHER="$!"; remember "$LAUNCHER"
  HOLDER=; wait_for test -s "$ready" && HOLDER="$(cat "$ready")"
  [ -n "$HOLDER" ] || { fail "setup: the namespaced holder did not start: $(cat "$FX/outside/bwrap.err")"; return 1; }
  remember "$HOLDER"
  [ -r "/proc/$HOLDER/cwd" ] && readlink "/proc/$HOLDER/cwd" > /dev/null && ls "/proc/$HOLDER/fd" > /dev/null \
    || { fail "setup: the holder $HOLDER is not readable"; return 1; }
}
ns_names() { # ns_names <inside path> <host dir>: the holder's path names the host directory (device and inode)
  local inside
  inside="$(stat -c %d:%i "/proc/$HOLDER/root$1" 2>/dev/null)"
  [ "$inside" = "$(stat -c %d:%i "$2")" ] || { fail "setup: $1 in the holder's namespace is '$inside', not $2"; return 1; }
}
ns_alone() { # ns_alone <entry> [allowed identity]: no argv text, cwd or handle of holder or launcher in the entry but the allowed one
  local pid link id ids
  ids="$(find "$1" -printf '%D:%i\n')"
  for pid in "$LAUNCHER" "$HOLDER"; do
    if grep -qaF -- "$1" "/proc/$pid/cmdline"; then fail "setup: process $pid carries the entry path as text"; fi
    for link in "/proc/$pid/cwd" "/proc/$pid"/fd/*; do
      id="$(stat -L -c %d:%i "$link" 2>/dev/null)" || continue
      [ -n "${2:-}" ] && [ "$id" = "$2" ] && [ "$pid" = "$HOLDER" ] && continue
      if printf '%s\n' "$ids" | grep -qxF -- "$id"; then fail "setup: $link of process $pid is inside the entry"; fi
    done
  done
}
ns_kept() { # ns_kept <entry>: the pass ran; the entry is kept whole by a FOUND reference, the sibling went
  pass "c$CASE" real real
  held_deep "$1"
  gone "$1-unused" 'the unused sibling with the same contents (the control)'
  expect dirs-kept-live 1; expect processes-in-doubt 0
}
ns_possible() { command -v bwrap >/dev/null 2>&1 && bwrap --dev-bind / / true 2>/dev/null; }
NS_SKIP='bwrap is missing or cannot make a mount namespace here: no other way to start one without root'

begin 40 'T1: a bind of a DESCENDANT of the entry; the only reference is an argument through it'
root="$(mkroot "$CO")"; entry="$root/live"; fill_deep "$entry"; fill_deep "$entry-unused"
if ! ns_possible; then skip "$NS_SKIP"; else
  ns_args --die-with-parent --dev-bind / / --tmpfs /mnt --bind "$entry/nested" /mnt/view --chdir /
  ns_hold "$FX/outside/ready" /mnt/view/deeper && ns_names /mnt/view/deeper "$entry/nested/deeper" && ns_alone "$entry" && ns_kept "$entry"
fi
finish

begin 41 'T2: the same bind; the only reference is the holder'"'"'s cwd through it'
root="$(mkroot "$CO")"; entry="$root/live"; fill_deep "$entry"; fill_deep "$entry-unused"
if ! ns_possible; then skip "$NS_SKIP"; else
  ns_args --die-with-parent --dev-bind / / --tmpfs /mnt --bind "$entry/nested" /mnt/view --chdir /mnt/view/deeper
  ns_hold "$FX/outside/ready" && ns_names /mnt/view/deeper "$entry/nested/deeper" \
    && [ "$(stat -L -c %d:%i "/proc/$HOLDER/cwd")" = "$(stat -c %d:%i "$entry/nested/deeper")" ] \
    && ns_alone "$entry" "$(stat -c %d:%i "$entry/nested/deeper")" && ns_kept "$entry"
fi
finish

begin 42 'T3: the same bind; the only reference is an open handle through it (a file, then a directory)'
root="$(mkroot "$CO")"; entry="$root/live"; fill_deep "$entry"; fill_deep "$entry-unused"; payload "$entry/nested/deeper/held-file"
if ! ns_possible; then skip "$NS_SKIP"; else
  ns_args --die-with-parent --dev-bind / / --tmpfs /mnt --bind "$entry/nested" /mnt/view --chdir /
  HOLD_FILE=/mnt/view/deeper/held-file ns_hold "$FX/outside/ready" && ns_names /mnt/view/deeper/held-file "$entry/nested/deeper/held-file" \
    && ns_alone "$entry" "$(stat -c %d:%i "$entry/nested/deeper/held-file")" && ns_kept "$entry"
  exists "$entry/nested/deeper/held-file" 'the held file (its lease is refused)'
  stop_holders; fill_deep "$entry-unused"
  HOLD_DIR=/mnt/view/deeper ns_hold "$FX/outside/ready" && ns_names /mnt/view/deeper "$entry/nested/deeper" \
    && ns_alone "$entry" "$(stat -c %d:%i "$entry/nested/deeper")" && ns_kept "$entry"
fi
finish

begin 43 'T4: the bind reached through a second step inside the namespace (a symlink into the mount)'
root="$(mkroot "$CO")"; entry="$root/live"; fill_deep "$entry"; fill_deep "$entry-unused"
if ! ns_possible; then skip "$NS_SKIP"; else
  ns_args --die-with-parent --dev-bind / / --tmpfs /mnt --bind "$entry/nested" /mnt/view --symlink /mnt/view/deeper /mnt/link --chdir /
  ns_hold "$FX/outside/ready" /mnt/link && ns_names /mnt/view/deeper "$entry/nested/deeper" \
    && { [ "$(readlink "/proc/$HOLDER/root/mnt/link")" = /mnt/view/deeper ] || fail 'setup: /mnt/link does not point into the mount'; } \
    && ns_alone "$entry" && ns_kept "$entry"
fi
finish

begin 44 'T5: the holder'"'"'s ROOT is a descendant of the entry; the argument is a path from that root'
root="$(mkroot "$CO")"; entry="$root/live"; fill_deep "$entry"; fill_deep "$entry-unused"
if ! ns_possible; then skip "$NS_SKIP"; else
  # Its cwd is /usr (outside), and its ready file sits on a bind of the case's outside directory.
  ns_args --die-with-parent --bind "$entry/nested" / --ro-bind /usr /usr --symlink usr/lib /lib --symlink usr/lib64 /lib64 \
    --symlink usr/bin /bin --proc /proc --dev /dev --bind "$FX/outside" /outside --chdir /usr
  if ! bwrap --args 3 python3 -c 'pass' 3< "$FX/outside/bwrap-args" 2>/dev/null; then
    skip 'bwrap cannot start python3 in a root inside the entry here (no /usr/lib64 layout?)'
  else
    ns_hold /outside/ready /deeper && ns_names /deeper "$entry/nested/deeper" \
      && [ "$(stat -L -c %d:%i "/proc/$HOLDER/root")" = "$(stat -c %d:%i "$entry/nested")" ] \
      && ns_alone "$entry" && ns_kept "$entry"
  fi
fi
finish

begin 45 'T7 and T8: a namespace keeps only what it reaches; one whose mounts and references are all outside keeps nothing'
root="$(mkroot "$CO")"; fill_deep "$root/a"; fill_deep "$root/b"; mkdir -p "$FX/outside/work/x"
if ! ns_possible; then skip "$NS_SKIP"; else
  ns_args --die-with-parent --dev-bind / / --tmpfs /mnt --bind "$root/a/nested" /mnt/view --chdir /
  ns_hold "$FX/outside/ready" /mnt/view/deeper && ns_names /mnt/view/deeper "$root/a/nested/deeper" && ns_alone "$root/a"
  held_a="$HOLDER"
  # T8: every mount of its namespace is outside every candidate (/usr, a fresh /proc and /dev, the case's outside dir).
  ns_args --die-with-parent --ro-bind /usr /usr --symlink usr/lib /lib --symlink usr/lib64 /lib64 --symlink usr/bin /bin \
    --proc /proc --dev /dev --bind "$FX/outside" /outside --chdir /outside/work
  ns_hold /outside/ready /outside/work/x && ns_names /outside/work/x "$FX/outside/work/x" && ns_alone "$root/b"
  # A mount root is a path inside its filesystem: the base's tail below the fixture directory names it.
  if grep -qF -- "${BASE#"$T"}" "/proc/$HOLDER/mountinfo"; then fail 'setup: a T8 mount is inside the temp base'; fi
  pass c45 real real
  HOLDER="$held_a"; held_deep "$root/a"
  gone "$root/b" 'b: nothing references it, and the outside namespace keeps nothing (T7, T8)'
  expect dirs-kept-live 1; expect root-removed 1; expect processes-in-doubt 0
fi
finish

begin 46 'a stacked filesystem over a directory inside the entry (overlay, lower dir inside): doubt, and doubt keeps'
root="$(mkroot "$CO")"; entry="$root/live"; fill_deep "$entry"; fill_deep "$entry-unused"
if ! ns_possible || ! bwrap --dev-bind / / --tmpfs /mnt --overlay-src "$entry/nested" --tmp-overlay /mnt/ov true 2>/dev/null; then
  skip 'bwrap cannot make an overlay here (bubblewrap before 0.10, or no unprivileged overlay)'
else
  ns_args --die-with-parent --dev-bind / / --tmpfs /mnt --overlay-src "$entry/nested" --tmp-overlay /mnt/ov --chdir /
  ns_hold "$FX/outside/ready" /mnt/ov/deeper && ns_alone "$entry"
  [ "$(cat "/proc/$HOLDER/root/mnt/ov/deeper/closed-file" | wc -c)" -eq 4096 ] || fail 'setup: the overlay does not show the lower dir'
  pass c46 real real
  held_deep "$entry"
  exists "$entry-unused/closed-file" 'the unused sibling: an overlay object is doubt, which keeps every candidate'
  expect processes-in-doubt 1; expect root-removed 0
fi
finish

begin 47 'FUSE (owner ruling 2026-11-15): a reference onto FUSE keeps nothing and is counted; the same process'"'"'s other references keep; an unknown type stays doubt'
# No FUSE daemon is installed here, so the helper runs through a wrapper that reports one other
# mount (/dev/shm's) with the given type in every mount table it parses: the D8 condition in the
# helper reads that type exactly as it reads a real line. The candidates' own mount is unchanged.
root="$(mkroot "$CO")"; fill_entry "$root/live"; fill_entry "$root/unrelated"
fake_type() { # fake_type <type>: $FX/outside/fake-lib runs the real helper with /dev/shm's mount reported as <type>
  mkdir -p "$FX/outside/fake-lib"
  printf '%s\n%s\n%s\n' "$HELPER" /dev/shm "$1" > "$FX/outside/fake-lib/fake.conf"
  cat > "$FX/outside/fake-lib/temp-hygiene-helper.py" <<'PY'
import dataclasses, importlib.util, os, sys
sys.dont_write_bytecode = True  # no __pycache__ beside the real helper in the checkout
here = os.path.dirname(os.path.abspath(__file__))
real, fake_at, fake_type = open(os.path.join(here, "fake.conf")).read().split("\n")[:3]
spec = importlib.util.spec_from_file_location("helper", real)
helper = importlib.util.module_from_spec(spec)
sys.modules["helper"] = helper
spec.loader.exec_module(helper)
fd = os.open(fake_at, helper.PATH_DIR)
ident = helper.Statx().mount(fd)[0]
os.close(fd)
parse = helper.parse_mountinfo
def faked(data):
    mounts, complete = parse(data)
    if ident in mounts:
        mounts[ident] = dataclasses.replace(mounts[ident], fstype=fake_type)
    return mounts, complete
helper.parse_mountinfo = faked
sys.exit(helper.main())
PY
}
FAKE_SEAM='TH_LIB_DIR="$FX/outside/fake-lib"'
classes="$(HELPER="$HELPER" python3 -I -B -c '
import importlib.util, os, sys
spec = importlib.util.spec_from_file_location("helper", os.environ["HELPER"])
helper = importlib.util.module_from_spec(spec); sys.modules["helper"] = helper; spec.loader.exec_module(helper)
print(" ".join("%s=%s" % (t, helper.is_fuse(t)) for t in ("fuse", "fuse.x", "fuse.portal", "fusectl", "fuseblk", "xfuse.y", "overlay")))')"
[ "$classes" = 'fuse=True fuse.x=True fuse.portal=True fusectl=False fuseblk=False xfuse.y=False overlay=False' ] \
  || fail "the FUSE type rule: $classes"
if [ ! -d /dev/shm ] || [ "$(stat -c %d /dev/shm)" = "$(stat -c %d "$BASE")" ]; then
  skip '/dev/shm is missing or on the temp base'"'"'s own filesystem: no second mount to report as FUSE'
else
  shm_holder() { # a process whose only reference outside the system is its cwd, /dev/shm
    ( cd /dev/shm && exec sleep 600 ) < /dev/null > /dev/null 2>&1 9>&- 10>&- 11>&- &
    remember "$!"; HOLDER="$!"; wait_for cwd_is "$HOLDER" /dev/shm || fail 'setup: the /dev/shm holder did not start'
  }
  # (a) The cwd resolves onto FUSE: no keep, no doubt, counted with a stderr line naming it.
  fake_type fuse.x; shm_holder
  pass c47a real real "$FAKE_SEAM"
  kill -0 "$HOLDER" 2>/dev/null || fail 'the FUSE holder did not stay alive through the pass'
  gone "$root/unrelated" 'unrelated: the only live reference is on FUSE, judged in its own tree'
  expect fuse-processes 1; expect processes-in-doubt 0; expect dirs-kept-live 0
  grep -q "1 live process(es) have a reference on a FUSE filesystem.*owner ruling 2026-11-15.*first five: $HOLDER sleep" "$ERR" \
    || fail "no FUSE stderr line naming the holder: $(cat "$ERR")"
  stop_holders
  # (b) Control: the same kind of holder ALSO names the entry on the candidates' own filesystem: kept whole.
  fill_entry "$root/live"; fill_entry "$root/unrelated"
  hold_arg "$root/live" /dev/shm
  pass c47b real real "$FAKE_SEAM"
  held_whole "$root/live"
  gone "$root/unrelated" 'unrelated: nothing but the FUSE cwd could reach it'
  expect fuse-processes 1; expect processes-in-doubt 0; expect dirs-kept-live 1
  stop_holders
  # (c) A type the code does not know is still doubt, and doubt keeps every candidate (overlay: case 46).
  fill_entry "$root/unrelated"
  fake_type unknownfs; shm_holder
  pass c47c real real "$FAKE_SEAM"
  exists "$root/unrelated/closed-file" 'unrelated: a reference on an unknown filesystem type is doubt, which keeps'
  expect processes-in-doubt 1; expect fuse-processes 0
  # (d) FUSE processes are counted even when the temp base has no candidate directories.
  # The existing holder still has the FUSE cwd; this fresh base has no checkout root or parent.
  fake_type fuse.x
  mkdir -p "$FX/empty-tmp"
  PASS_TMPDIR="$FX/empty-tmp" pass c47d real real "$FAKE_SEAM"
  kill -0 "$HOLDER" 2>/dev/null || fail 'the FUSE holder did not stay alive through the empty-base pass'
  expect fuse-processes 1; expect processes-in-doubt 0; expect root-state absent
fi
finish

# ---- every captured summary names a fixture base ---------------------------------------------
CASE=bases
lines="$(wc -l < "$T/summaries")"
[ "$lines" -ge "$PASSN" ] || fail "only $lines summary lines for $PASSN passes"
outside="$(grep -v "^temp-hygiene: mode=[^ ]* base=$T/" "$T/summaries" || true)"
[ -z "$outside" ] || fail "a summary line names a base outside the fixtures: $outside"

foreign=0; [ ! -s "$T/foreign-doubt" ] || { foreign="$(wc -l < "$T/foreign-doubt")"; sed 's/^/  left out (outside the case, in doubt): /' "$T/foreign-doubt"; }
fused=0; [ ! -s "$T/foreign-fuse" ] || fused="$(wc -l < "$T/foreign-fuse")"
echo "temp-hygiene self-test: $CASES cases ran ($SKIPS sub-cases skipped with a reason), $PASSN passes, $lines summary lines, $foreign foreign processes in doubt left out, $fused foreign process-passes on FUSE left out, $FAILS failures"
[ "$FAILS" -eq 0 ]
