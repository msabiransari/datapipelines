#!/usr/bin/env bash
# temp-hygiene-test.sh — the self-test of scripts/lib/temp-hygiene.sh (#983, DEVELOPMENT.md §9.7).
# Run by `scripts/pregate.sh --self-test`, and so by gate.sh's pregate self-test stage.
#
# Fixture temp directories only: every pass of the library gets TMPDIR = a fixture directory
# (the temp base), so nothing outside $T is ever a candidate. The self-test fails when any
# summary line it captured names a base outside its fixtures, and it prints the number of
# cases it ran. Seams are the library's own shell functions, replaced inside the pass's
# subshell after sourcing; no environment variable switches the lease or the scan off.
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
finish() {
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

# One pass of the library in a subshell: pass <label> <root-mode> <shared-mode> [seam code].
pass() {
  local label="$1" rmode="$2" smode="$3" seams="${4:-}"
  PASSN=$((PASSN + 1))
  OUT="$FX/out.$PASSN"; ERR="$FX/err.$PASSN"; LIST="$FX/list.$PASSN"
  (
    export TMPDIR="$BASE"
    # shellcheck source=lib/temp-hygiene.sh
    source "$LIB"
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
pass c14a real real 'th::_remover_extra_args() { [ "$1" != gone ] || echo --stop-after 1; }'
exists "$gone_root" 'the part-way root'; exists "$gone_root.checkout" 'the part-way root'"'"'s record'
expect roots-unrecorded 1
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

# ---- every captured summary names a fixture base ---------------------------------------------
CASE=bases
lines="$(wc -l < "$T/summaries")"
[ "$lines" -ge "$PASSN" ] || fail "only $lines summary lines for $PASSN passes"
outside="$(grep -v "^temp-hygiene: mode=[^ ]* base=$T/" "$T/summaries" || true)"
[ -z "$outside" ] || fail "a summary line names a base outside the fixtures: $outside"

echo "temp-hygiene self-test: $CASES cases ran ($SKIPS sub-cases skipped with a reason), $PASSN passes, $lines summary lines, $FAILS failures"
[ "$FAILS" -eq 0 ]
