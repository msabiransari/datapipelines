#!/usr/bin/env bash
# #472: execute the real wrappers against isolated fixtures. Never call Gradle.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/pregate-2b-lib.sh"
source "$ROOT/scripts/pregate-results-lib.sh"
T="$(mktemp -d /tmp/pregate-safety.XXXXXX)"
trap 'rm -rf "$T"' EXIT

fail() { echo "  FAIL $*" >&2; exit 1; }
ok() { echo "  ok   $*"; }

F="$T/fixture"
pgres::_fixture "$F"
mkdir -p "$F/tests/browser-tests/src/test/kotlin/co/x"
src="$F/tests/browser-tests/src/test/kotlin/co/x"
build="$F/tests/browser-tests/build.gradle.kts"
printf '// fixture\n' > "$build"
printf 'package co.x\nabstract class BrowserSuite\n' > "$src/BrowserSuite.kt"
for n in 1 2 3 4 5 6; do
  cat > "$src/Case${n}Test.kt" <<EOF
package co.x
class Case${n}Test : BrowserSuite() {
    @org.junit.jupiter.api.Test
    fun run() {}
}
EOF
done

plan="$(pg2b::plan_module browser-tests "$build" "$src" "$src/BrowserSuite.kt")"
[ "$plan" = deferred ] || fail "shared base expanded past five classes: $plan"
ok 'large shared-base selection deferred'
plan="$(pg2b::plan_module browser-tests "$build" "$src" "$src/Case1Test.kt")"
[ "$plan" = $'focused\nco.x.Case1Test' ] || fail 'changed concrete class not focused'
ok 'one concrete class stays focused'
plan="$(pg2b::plan_module browser-tests "$build" "$src" "$src/Case"{1,2,3,4,5}Test.kt)"
[ "$(printf '%s\n' "$plan" | wc -l)" -eq 6 ] || fail 'five-class boundary'
ok 'five-class boundary remains focused'

# The argv boundary must independently refuse accidental caller regressions.
for plan in whole 'focused' 'focused *' 'focused co.x.A co.x.B co.x.C co.x.D co.x.E co.x.F'; do
  words=(); read -r -a words <<< "$plan"
  if args="$(pg2b::gradle_args browser-tests "${words[@]}" 2>/dev/null)"; then
    fail "unsafe argv accepted: $plan"
  fi
  [ -z "$args" ] || fail "unsafe plan emitted partial argv: $plan"
done
[ -z "$(pg2b::gradle_args browser-tests deferred)" ] || fail 'deferred emitted argv'
ok 'unfiltered, empty, wildcard and oversized argv refused'

# The fixture has a changed browser build file and >5 changed classes. The
# real pregate must defer them, preserve that fact, and still run focused E2E.
pgres::_run_fixture "$F" || fail 'bounded pregate fixture'
! grep -q ':tests:browser-tests:test' "$F/pregate-argv.log" || fail 'browser task launched'
grep -q 'co.x.ChangedE2eTest' "$F/pregate-argv.log" || fail 'focused E2E missing'
grep -q 'deferred=tests/browser-tests' "$F/.pregate-logs/0-verdict.log" || fail 'deferred verdict missing'
grep -q 'status=deferred' "$F"/.pregate-logs/runs/*/MANIFEST.txt || fail 'deferred manifest missing'
while IFS= read -r args; do
  [[ "$args" == *'--no-parallel --max-workers=1 -Pdp.test.forks=1 -Pdp.test.forks.e2e=1'* ]] || fail 'unbounded Gradle invocation'
  [[ " $args " == *' --build-cache '* ]] || fail "pregate invocation without --build-cache (#480): $args"
done < "$F/pregate-argv.log"
[ "$(wc -l < "$F/pregate-argv.log")" -ge 4 ] || fail 'too few recorded Gradle invocations to check'
ok 'real pregate defers browser coverage and limits every Gradle invocation'
ok 'every pregate Gradle invocation passes --build-cache (#480)'

# #596: console and retained manifest must name the module's actual full-run home.
for module in browser-tests integration-tests; do
  case "$module" in
    browser-tests) destination='CI on next'; reason=CI-next-required ;;
    integration-tests) destination='Gate A and CI'; reason=Gate-A-and-CI-required ;;
  esac
  D="$T/deferral-$module"
  pgres::_fixture "$D"
  mkdir -p "$D/tests/$module"
  printf '// changed build\n' >> "$D/tests/$module/build.gradle.kts"
  plan="$(pg2b::plan_module "$module" "$D/tests/$module/build.gradle.kts" \
    "$D/tests/$module/src/test/kotlin" "$D/tests/$module/build.gradle.kts" 2> "$T/deferral.out")"
  [ "$plan" = deferred ] || fail "$module build change was not deferred"
  grep -qF "deferred to $destination)" "$T/deferral.out" || fail "$module deferral destination: $(cat "$T/deferral.out")"
  pgres::_run_fixture "$D" || fail "$module deferral fixture"
  grep -q "stage=2b-$module status=deferred .*${reason}" "$D"/.pregate-logs/runs/*/MANIFEST.txt || fail "$module manifest destination"
done
ok 'browser deferrals name CI on next; integration deferrals name Gate A and CI (console and manifest)'

# #576: exercise the real git-diff planner, including a committed layout-only diff.
L="$T/layout fixture"
pgres::_fixture "$L"
layout='modules/web/src/main/resources/templates/layouts/default.html'
mkdir -p "$L/$(dirname "$layout")"
printf '<html>base</html>\n' > "$L/$layout"
(cd "$L" && git add -A && git commit -qm 'layout fixture base' && git branch -f main HEAD)
printf '<html>changed shell</html>\n' >> "$L/$layout"
(cd "$L" && git add "$layout" && git commit -qm 'layout-only change')
(cd "$L" && bash scripts/pregate.sh --plan HEAD^) > "$T/layout-plan"
cat "$T/layout-plan"
expected=$'focused\nco.datapipelines.integration.JarSmokeE2eTest\nco.datapipelines.integration.LocalLoginE2eTest\nco.datapipelines.integration.WriteSurfaceStampingE2eTest'
actual="$(sed -n '/^selection tests\/integration-tests:$/,/^deferred=/p' "$T/layout-plan" | sed '1d;$d')"
[ "$actual" = "$expected" ] || fail "layout-only selection: expected [$expected], got [$actual]"
! grep -q '^selection tests/browser-tests:' "$T/layout-plan" || fail 'layout selected browser tests'
ok 'committed layout-only diff selects exactly the three shell-render integration classes'

# A diff path with spaces still selects the fixed names, and repeated layouts or
# a changed allowlisted class must not consume the budget twice.
printf '<html>second shell</html>\n' > "$L/$(dirname "$layout")/another shell.html"
isrc="$L/tests/integration-tests/src/test/kotlin/co/x"
printf 'package co.datapipelines.integration\nclass JarSmokeE2eTest {\n    @org.junit.jupiter.api.Test\n    fun run() {}\n}\n' > "$isrc/ShellWitness.kt"
(cd "$L" && bash scripts/pregate.sh --plan HEAD^) > "$T/layout-deduplicated"
actual="$(sed -n '/^selection tests\/integration-tests:$/,/^deferred=/p' "$T/layout-deduplicated" | sed '1d;$d')"
[ "$actual" = "$expected" ] || fail 'layout and changed-test union was not deduplicated'
ok 'layout diff path with spaces and changed allowlisted class preserve the exact three-class selection'

# Union and deduplicate ordinary changed classes before applying the same budget.
for n in 1 2 3; do
  printf 'package co.x\nclass Extra%sTest {\n    @org.junit.jupiter.api.Test\n    fun run() {}\n}\n' "$n" > "$isrc/Extra${n}Test.kt"
  (cd "$L" && bash scripts/pregate.sh --plan HEAD^) > "$T/layout-budget-$n"
  if [ "$n" -le 2 ]; then
    grep -q '^focused$' "$T/layout-budget-$n" || fail "layout union with $n classes not focused"
    grep -q "^co.x.Extra${n}Test$" "$T/layout-budget-$n" || fail 'changed class missing from layout union'
  else
    grep -q '^deferred=tests/integration-tests$' "$T/layout-budget-$n" || fail 'six-class layout union not deferred'
    grep '^stage=2b ' "$T/layout-budget-$n" | grep -q 'command=(none)' || fail 'deferred layout union emitted argv'
  fi
done
cat "$T/layout-budget-3"
ok 'layout classes union with changed tests; five stay focused and six defer without truncation'

# #480: the cache is the pregate's alone. gate.sh's clean build and CI must compile from cold,
# so neither may pass the flag, and no project file may turn caching on for every build.
for cold in "$ROOT/scripts/gate.sh" "$ROOT"/.github/workflows/*.yml; do
  [ -f "$cold" ] || fail "cold-build file missing: $cold"
  [ "$(grep -cF -e '--build-cache' "$cold" || true)" -eq 0 ] || fail "--build-cache in a cold build: $cold"
done
for global in "$ROOT/gradle.properties" "$ROOT/scripts/gate.sh" "$ROOT"/.github/workflows/*.yml; do
  [ "$(grep -cF 'org.gradle.caching' "$global" || true)" -eq 0 ] || fail "org.gradle.caching set globally: $global"
done
ok 'gate.sh, CI workflows and gradle.properties never enable the build cache (#480)'

# #485: two pregate-class slots across checkouts, the gate exclusive, one holder per
# checkout, older copies respected, a memory floor. Every holder lives in a subshell;
# every contender closes the inherited descriptors, exactly like an independent session.
G="$T/other-checkout"
mkdir -p "$G/scripts/lib" "$G/.gate-logs"
cp "$ROOT/scripts/gate.sh" "$G/scripts/"
cp "$ROOT/scripts/lib/verification-lock.sh" "$G/scripts/lib/"
printf 'prior gate evidence\n' > "$G/.gate-logs/sentinel"
export XDG_CACHE_HOME="$F/.fixture-cache"
CACHE="$XDG_CACHE_HOME/datapipelines"
source "$ROOT/scripts/lib/verification-lock.sh"
# The pre-#485 library verbatim: what a lane copy made before this change runs.
cat > "$T/old-verification-lock.sh" <<'OLDLOCK'
# shellcheck shell=bash
# One gate/pregate per user across worktrees. Acquire BEFORE deleting logs or
# starting Gradle. Keep fd 9 in the wrapper, close it in Gradle children so an
# idle daemon cannot retain the lock after the wrapper exits. Never unlink it:
# replacing a locked inode would let a second wrapper acquire a different lock.
verification::lock() {
  local kind="$1" root="$2"
  local dir="${XDG_CACHE_HOME:-$HOME/.cache}/datapipelines"
  command -v flock >/dev/null 2>&1 || {
    echo "verification refused: flock is required (install util-linux)." >&2
    return 2
  }
  mkdir -p "$dir" || return 2
  exec 9>>"$dir/verification.lock" || return 2
  if ! flock -n 9; then
    echo "verification busy: another gate/pregate holds $dir/verification.lock" >&2
    if [ -r "$dir/verification-holder.txt" ]; then
      cat "$dir/verification-holder.txt" >&2
    fi
    exec 9>&-
    return 75
  fi
  printf 'kind=%s pid=%s cwd=%s started=%s\n' "$kind" "$$" "$root" \
    "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" > "$dir/verification-holder.txt" || {
      exec 9>&-
      return 2
    }
}
OLDLOCK
contend() { # script → rc, combined output in $T/refused; a gate fixture is only ever refused
  local args=()
  [[ "$1" != */pregate.sh ]] || args=(HEAD)
  rc=0
  bash "$1" "${args[@]}" 9>&- 10>&- 11>&- > "$T/refused" 2>&1 || rc=$?
}
argv_count() { wc -l < "$F/pregate-argv.log"; }
evidence_state() { (cd "$F" && find .pregate-logs pregate-argv.log -type f -exec cksum {} + | sort); }
refused_pregate() { # case → the pregate contender refused before any work
  local before; before="$(evidence_state)"
  contend "$F/scripts/pregate.sh"
  [ "$rc" -eq 75 ] || fail "$1: pregate not refused ($rc)"
  [ "$before" = "$(evidence_state)" ] || fail "$1: refused pregate touched evidence or launched Gradle"
}
refused_gate() { # case → the gate contender refused before erasing evidence
  contend "$G/scripts/gate.sh"
  [ "$rc" -eq 75 ] || fail "$1: gate not refused ($rc)"
  [ "$(cat "$G/.gate-logs/sentinel")" = 'prior gate evidence' ] || fail "$1: refused gate erased evidence"
}

# (1) one pregate-class holder elsewhere: a pregate in another checkout RUNS on slot 1.
(
  verification::lock fixture "$T" || fail 'case 1: holder'
  before="$(argv_count)"
  contend "$F/scripts/pregate.sh"
  [ "$rc" -eq 0 ] || fail "case 1: second pregate refused ($rc): $(grep -m2 '^verification' "$T/refused")"
  [ "$(argv_count)" -gt "$before" ] || fail 'case 1: second pregate launched no Gradle'
  grep -q "^kind=fixture pid=.* cwd=$T " "$CACHE/verification-holder.0.txt" || fail 'case 1: holder record'
  grep -q "^kind=pregate pid=.* cwd=$F " "$CACHE/verification-holder.1.txt" || fail 'case 1: second holder record'
)
ok 'case 1: two pregate-class holders coexist in different checkouts'

# (2) both slots held: a third pregate-class contender is refused naming both.
(
  verification::lock fixture-a "$T/a" || fail 'case 2: holder a'
  (
    verification::lock fixture-b "$T/b" || fail 'case 2: holder b'
    refused_pregate 'case 2'
    grep -q "slot=0 kind=fixture-a .*cwd=$T/a " "$T/refused" || fail 'case 2: holder a not named'
    grep -q "slot=1 kind=fixture-b .*cwd=$T/b " "$T/refused" || fail 'case 2: holder b not named'
  )
)
ok 'case 2: a third pregate-class contender is refused with both holders named'

# (3) one pregate-class holder: a gate contender is refused and erases nothing.
(
  verification::lock fixture "$T" || fail 'case 3: holder'
  refused_gate 'case 3'
  grep -q "slot=0 kind=fixture .*cwd=$T " "$T/refused" || fail 'case 3: holder not named'
  ! verification::_held "$CACHE/verification.slot.1" || fail 'case 3: refused gate kept slot 1'
  ! verification::_held "$CACHE/verification.lock" || fail 'case 3: refused gate kept the old lock'
)
ok 'case 3: a gate is refused while a pregate-class holder lives, releasing what it took'

# (3b) only slot 1 held: the gate takes slot 0 first, then must give it back.
(
  exec 9>>"$CACHE/verification.slot.1"; flock -n 9 || fail 'case 3b: slot-1 holder'
  printf 'kind=fixture-1 pid=%s cwd=%s started=x\n' "$$" "$T/one" > "$CACHE/verification-holder.1.txt"
  refused_gate 'case 3b'
  grep -q "slot=1 kind=fixture-1 .*cwd=$T/one " "$T/refused" || fail 'case 3b: slot-1 holder not named'
  ! verification::_held "$CACHE/verification.slot.0" || fail 'case 3b: refused gate kept slot 0'
  ! verification::_held "$CACHE/verification.lock" || fail 'case 3b: refused gate kept the old lock'
  # A caller that continues after 75 (the process exit above releases anyway).
  (
    exec 9>&- 10>&- 11>&-
    rc=0; verification::lock gate "$T/in-process" 2> "$T/refused" || rc=$?
    [ "$rc" -eq 75 ] || fail "case 3b: in-process gate not refused ($rc)"
    ! verification::_held "$CACHE/verification.slot.0" || fail 'case 3b: in-process refused gate kept slot 0'
    ! verification::_held "$CACHE/verification.lock" || fail 'case 3b: in-process refused gate kept the old lock'
  )
)
ok 'case 3b: a gate that gets one slot and not the other releases what it took'

# (4) a gate holder: both contenders are refused, nothing erased, nothing launched.
(
  verification::lock gate "$T" || fail 'case 4: gate holder'
  refused_pregate 'case 4'
  grep -q "kind=gate .*cwd=$T " "$T/refused" || fail 'case 4: gate not named to pregate'
  refused_gate 'case 4'
  grep -q "kind=gate .*cwd=$T " "$T/refused" || fail 'case 4: gate not named to gate'
)
ok 'case 4: a gate holder refuses pregate and gate before either touches evidence'

# (5) a live holder in THIS checkout: refused for the checkout even with slot 1 free.
(
  verification::lock fixture "$F" || fail 'case 5: holder'
  refused_pregate 'case 5'
  grep -q 'this checkout already has a live holder' "$T/refused" || fail 'case 5: same-checkout reason missing'
  grep -q "slot=0 kind=fixture .*cwd=$F " "$T/refused" || fail 'case 5: holder not named'
  ! verification::_held "$CACHE/verification.slot.1" || fail 'case 5: slot 1 was not free'
)
ok 'case 5: a second holder for one checkout is refused with a slot free'

# (6) an older copy holding verification.lock: both new contenders are refused.
(
  exec 9>>"$CACHE/verification.lock"; flock -n 9 || fail 'case 6: legacy holder'
  printf 'kind=legacy pid=%s cwd=%s started=x\n' "$$" "$T/legacy" > "$CACHE/verification-holder.txt"
  refused_pregate 'case 6'
  grep -q "kind=legacy .*cwd=$T/legacy " "$T/refused" || fail 'case 6: legacy holder not named to pregate'
  refused_gate 'case 6'
  grep -q "kind=legacy .*cwd=$T/legacy " "$T/refused" || fail 'case 6: legacy holder not named to gate'
)
ok 'case 6: an older copy holding the pre-#485 lock refuses new pregate and gate'

# (6b) a new gate holds the pre-#485 lock too: an older copy is refused, naming the gate.
(
  verification::lock gate "$T" || fail 'case 6b: gate holder'
  rc=0
  bash -c 'source "$1"; verification::lock old-copy "$2"' _ "$T/old-verification-lock.sh" "$T/old" \
    9>&- 10>&- 11>&- > "$T/refused" 2>&1 || rc=$?
  [ "$rc" -eq 75 ] || fail "case 6b: older copy not refused by a gate ($rc)"
  grep -q "kind=gate .*cwd=$T " "$T/refused" || fail 'case 6b: gate not named to the older copy'
)
ok 'case 6b: a gate refuses an older pregate copy'

# (7) the kernel released every slot and the old lock: a gate takes them all after exit.
(verification::lock gate "$T") || fail 'case 7: slots or lock not released after exit'
pgres::_run_fixture "$F" || fail 'case 7: pregate could not run after holders exited'
ok 'case 7: every slot and the old lock are released on exit'

# (9)/(10) the memory floor refuses before any record or evidence; 0 admits.
FC="$T/floor-cache"
memtotal="$(awk '/^MemTotal:/ { print int($2 / 1024) + 1; exit }' /proc/meminfo)"
before="$(evidence_state)"
rc=0
DATAPIPELINES_VERIFICATION_MEM_FLOOR_MB="$memtotal" XDG_CACHE_HOME="$FC" \
  bash "$F/scripts/pregate.sh" HEAD 9>&- > "$T/refused" 2>&1 || rc=$?
[ "$rc" -eq 75 ] || fail "case 9: pregate under the floor not refused ($rc)"
grep -q "^verification refused: MemAvailable [0-9]* MB below floor $memtotal MB" "$T/refused" || fail 'case 9: floor message'
[ "$before" = "$(evidence_state)" ] || fail 'case 9: refused pregate touched evidence or launched Gradle'
[ ! -e "$FC" ] || fail 'case 9: refused pregate created the cache or a holder record'
rc=0
(DATAPIPELINES_VERIFICATION_MEM_FLOOR_MB="$memtotal" XDG_CACHE_HOME="$FC" verification::lock gate "$T") 2> "$T/refused" || rc=$?
[ "$rc" -eq 75 ] && [ ! -e "$FC" ] || fail "case 9: gate under the floor not refused ($rc)"
before="$(argv_count)"; rc=0
DATAPIPELINES_VERIFICATION_MEM_FLOOR_MB=0 XDG_CACHE_HOME="$FC" \
  bash "$F/scripts/pregate.sh" HEAD 9>&- > "$T/refused" 2>&1 || rc=$?
[ "$rc" -eq 0 ] && [ "$(argv_count)" -gt "$before" ] || fail "case 10: floor 0 did not admit ($rc)"
garbage="$(DATAPIPELINES_VERIFICATION_MEM_FLOOR_MB=12x verification::_mem_floor_mb 2> "$T/garbage")"
[ "$garbage" = "$VERIFICATION_MEM_FLOOR_DEFAULT_MB" ] && grep -q "'12x' is not an integer" "$T/garbage" || fail 'garbage floor'
[ "$(DATAPIPELINES_VERIFICATION_MEM_FLOOR_MB= verification::_mem_floor_mb 2>&1)" = "$VERIFICATION_MEM_FLOOR_DEFAULT_MB" ] || fail 'empty floor'
ok 'cases 9-10: the floor refuses gate and pregate before any record; 0 admits; garbage reads the default'
echo '  resource safety self-test: PASS'

# #479: the production wrappers, planner and evidence reducer, with no real tools.
run_fixture() { # fixture [flags...]
  local fixture="$1"; shift
  (cd "$fixture" && XDG_CACHE_HOME="$fixture/.fixture-cache" bash scripts/pregate.sh "$@" HEAD > pregate.out 2>&1)
}
run_dir() { local dirs=("$1"/.pregate-logs/runs/*); printf '%s' "${dirs[0]}"; }
P="$T/plan"
pgres::_fixture "$P"
(cd "$P" && XDG_CACHE_HOME="$P/.fixture-cache" bash scripts/pregate.sh --plan HEAD > "$T/preview") || fail 'preview exit'
[ ! -e "$P/.pregate-logs" ] && [ ! -e "$P/pregate-argv.log" ] && [ ! -e "$P/.fixture-cache" ] || fail 'preview mutated logs/cache or ran Gradle'
grep -q 'omission conditional' "$T/preview" || fail 'preview hid conditional guard omission'
run_fixture "$P" || fail 'plan execution'
rd="$(run_dir "$P")"
cmp "$T/preview" "$rd/PLAN.txt" || fail 'preview and execution plans differ'
# The Gradle stages against the Gradle stand-in's record; stage 0 is not Gradle (#797) and
# this fixture changes no dependency file, so its planned command is (none).
grep -qx 'stage=0 command=(none)' "$T/preview" || fail 'unchanged dependencies planned a scan'
sed -n -e '/^stage=0 /d' -e 's/^stage=[^ ]* command=//p' "$T/preview" > "$T/planned"
cmp "$T/planned" "$P/pregate-quoted.log" || fail 'planned and actual commands differ'
ok 'preview is read-only; exact planned and actual argv match'

# Invalid refs and arguments refuse before locks, evidence or processes.
I="$T/invalid"; pgres::_fixture "$I"
for arguments in '--help --unknown' '--unknown' '--plan nonexistent-ref-479' 'HEAD HEAD' '--plan --continue-on-failure HEAD' '--self-test HEAD'; do
  rc=0
  (cd "$I" && XDG_CACHE_HOME="$I/.fixture-cache" bash scripts/pregate.sh $arguments > "$T/invalid.out" 2>&1) || rc=$?
  [ "$rc" -eq 2 ] || fail "invalid input exit=$rc: $arguments"
done
[ ! -e "$I/.pregate-logs" ] && [ ! -e "$I/.fixture-cache" ] && [ ! -e "$I/pregate-argv.log" ] || fail 'invalid input mutated state'
ok 'invalid refs, flags and combinations fail before mutation'

# Staged edits belong to both plan and execution, and old bases retain inherited breadth.
(cd "$I" && git add modules/auth/src/main/kotlin/co/x/Auth.kt)
(cd "$I" && bash scripts/pregate.sh --plan HEAD) > "$T/staged" || fail 'staged plan'
grep -q '^modules/auth/src/main/kotlin/co/x/Auth.kt$' "$T/staged" || fail 'staged diff disappeared'
(cd "$I" && git branch main && git commit -qm inherited && git branch -f main HEAD && bash scripts/pregate.sh --plan HEAD^) > "$T/inherited" || fail 'inherited plan'
grep -q 'current-main comparison=different' "$T/inherited" || fail 'inherited base breadth hidden'
ok 'staged edits and inherited breadth remain visible'

# #757/#752: a lane is cut from the local main, which runs ahead of origin/main while a landed
# batch waits for CI on next. A bare run plans the lane's own diff; an explicit origin/main
# still plans the batch (the old default), so one fixture proves both directions.
A="$T/ahead-of-origin"; pgres::_fixture "$A"
mkdir -p "$A/tests/browser-tests/src/test/kotlin/co/x" "$A/docs"
printf '// fixture\n' > "$A/tests/browser-tests/build.gradle.kts"
printf '# guide\n' > "$A/docs/guide.md"
(cd "$A" && git add -A && git commit -qm 'fixture tree' && git branch -M main && git update-ref refs/remotes/origin/main HEAD)
batch='tests/browser-tests/src/test/kotlin/co/x/BatchBrowserTest.kt'
printf 'package co.x\nclass BatchBrowserTest {\n    @org.junit.jupiter.api.Test\n    fun run() {}\n}\n' > "$A/$batch"
(cd "$A" && git add -A && git commit -qm 'landed batch, not on origin yet' && git checkout -qb lane)
printf 'lane edit\n' >> "$A/docs/guide.md"
(cd "$A" && git commit -qam 'lane: docs only')
(cd "$A" && bash scripts/pregate.sh --plan) > "$T/ahead-bare" || fail 'bare plan ahead of origin'
changed="$(sed -n '/^changed files /,/^current-main comparison=/p' "$T/ahead-bare" | sed '1d;$d')"
[ "$changed" = docs/guide.md ] || fail "bare plan is not the lane's own diff: [$(printf '%s' "$changed" | tr '\n' ' ')]"
! grep -q '^selection tests/browser-tests:' "$T/ahead-bare" || fail "bare plan selected the batch's browser class: $batch"
grep -q '^base=main (default:main; origin/main is 1 behind) ' "$T/ahead-bare" || fail "bare base: $(head -1 "$T/ahead-bare")"
(cd "$A" && bash scripts/pregate.sh --plan origin/main) > "$T/ahead-origin" || fail 'explicit origin/main plan'
grep -q '^base=origin/main (explicit) ' "$T/ahead-origin" || fail "explicit base overridden: $(head -1 "$T/ahead-origin")"
grep -qxF "$batch" "$T/ahead-origin" || fail 'explicit origin/main no longer plans the batch file'
sed -n '/^selection tests\/browser-tests:$/,/^deferred=/p' "$T/ahead-origin" | grep -qx 'co.x.BatchBrowserTest' || fail 'explicit origin/main no longer selects the batch class'
(cd "$A" && XDG_CACHE_HOME="$A/.fixture-cache" bash scripts/pregate.sh > pregate.out 2>&1) || fail "bare run: $(tail -3 "$A/pregate.out")"
grep -q '^Pre-gate base=main (default:main; origin/main is 1 behind) ' "$A/pregate.out" || fail 'bare run console base'
grep -q '^PRE-GATE PASS base=main ' "$A/.pregate-logs/0-verdict.log" || fail 'bare run verdict base'
! grep -qE ':tests:browser-tests:|co\.x\.BatchBrowserTest' "$A/pregate-argv.log" || fail 'bare run launched browser work'
ok 'bare base is the local main ahead of origin/main; explicit origin/main still plans the batch (#757)'

# CI's checkout carries origin/main and no local main: the default falls back, and with neither
# ref the run refuses before any mutation instead of guessing a base.
O="$T/origin-only"; pgres::_fixture "$O"
(cd "$O" && git add -A && git commit -qm 'fixture tree' && git update-ref refs/remotes/origin/main HEAD && git branch -M trunk)
! git -C "$O" show-ref --verify --quiet refs/heads/main || fail 'origin-only fixture still has a local main'
printf '// lane\n' >> "$O/modules/auth/src/main/kotlin/co/x/Auth.kt"
(cd "$O" && bash scripts/pregate.sh --plan) > "$T/origin-only.plan" || fail 'bare plan without a local main'
grep -q '^base=origin/main (default:origin/main) ' "$T/origin-only.plan" || fail "fallback base: $(head -1 "$T/origin-only.plan")"
grep -qx 'modules/auth/src/main/kotlin/co/x/Auth.kt' "$T/origin-only.plan" || fail 'fallback plan lost the lane diff'
grep -qx 'current-main comparison=unavailable' "$T/origin-only.plan" || fail 'fallback claimed a local-main comparison'
git -C "$O" update-ref -d refs/remotes/origin/main
rc=0; (cd "$O" && XDG_CACHE_HOME="$O/.fixture-cache" bash scripts/pregate.sh > "$T/no-base.out" 2>&1) || rc=$?
[ "$rc" -eq 2 ] && [ ! -e "$O/.pregate-logs" ] && [ ! -e "$O/pregate-argv.log" ] || fail "no base ref: exit=$rc or state mutated"
ok 'without a local main the default is origin/main; with neither ref the run refuses (#757)'

# Gradle invocations the stand-in recorded; a run that stopped before any has no log.
argv_lines() { if [ -f "$1" ]; then wc -l < "$1"; else echo 0; fi; }
for stage in 0 1 2 2b 3 4; do
  X="$T/failure-$stage"; pgres::_fixture "$X" "fail-$stage"
  # Stage 0 fails on known vulnerabilities behind a changed dependency file (#797).
  [ "$stage" != 0 ] || pgres::_fixture_deps "$X" findings gradle/libs.versions.toml
  rc=0; run_fixture "$X" || rc=$?
  [ "$rc" -eq 1 ] || fail "failure-$stage did not FAIL"
  rd="$(run_dir "$X")"
  expected=0; seen=0
  for later in 0 1 2 2b 3 4; do
    [ "$later" = 0 ] || expected=$((expected + 1)) # stage 0 is not a Gradle invocation
    if [ "$later" = "$stage" ]; then seen=1; break; fi
  done
  [ "$seen" -eq 1 ] || fail 'fixture stage missing'
  [ "$(argv_lines "$X/pregate-argv.log")" -eq "$expected" ] || fail "fail-fast scheduled after $stage"
  for later in 0 1 2 2b 3 4; do
    if [ "$seen" -eq 2 ]; then
      grep -q "stage=stage-$later status=not-run exit=125 reason=after-$stage" "$rd/MANIFEST.txt" || fail "later stage $later not marked after $stage"
    fi
    [ "$later" != "$stage" ] || seen=2
  done
  grep -q '^PRE-GATE FAIL' "$X/.pregate-logs/0-verdict.log" || fail 'failed verdict missing'
  test -f "$rd/$(case "$stage" in 0) echo 0-deps-scan.log;; 1) echo 1-lint.log;; 2) echo 2-touched-modules.log;; 2b) echo 2b-tests-modules.log;; 3) echo 3-guards.log;; 4) echo 4-compile-all-tests.log;; esac)" || fail 'failed log not retained'
  case "$stage" in
    0) grep -q '^stage=stage-0 status=executed exit=1 reason=findings$' "$rd/MANIFEST.txt" || fail 'original stage exit lost' ;;
    *) grep -q "stage=stage-$stage status=executed exit=8" "$rd/MANIFEST.txt" || fail 'original stage exit lost' ;;
  esac
  ok "failure at stage $stage preserves evidence and stops scheduling"
  D="$T/diagnostic-$stage"; pgres::_fixture "$D" "fail-$stage"
  [ "$stage" != 0 ] || pgres::_fixture_deps "$D" findings gradle/libs.versions.toml
  rc=0; run_fixture "$D" --continue-on-failure || rc=$?
  [ "$rc" -eq 1 ] && [ "$(argv_lines "$D/pregate-argv.log")" -eq 5 ] || fail "diagnostic continuation after $stage"
  grep -q '^PRE-GATE FAIL.*diagnostic=1' "$D/.pregate-logs/0-verdict.log" || fail 'diagnostic promoted to PASS'
  ok "diagnostic stage $stage attempts later stages and stays FAIL"
done

X="$T/snapshot-failure"; pgres::_fixture "$X" snapshot-fail
rc=0; run_fixture "$X" || rc=$?
[ "$rc" -eq 1 ] && [ "$(wc -l < "$X/pregate-argv.log")" -eq 2 ] || fail 'snapshot failure did not stop scheduling'
rd="$(run_dir "$X")"
grep -q 'SNAPSHOT-FAILED' "$rd/MANIFEST.txt" || fail 'snapshot failure missing'
grep -q '^PRE-GATE FAIL.*mod=0.*snap=1' "$X/.pregate-logs/0-verdict.log" || fail 'snapshot failure overwrote Gradle exit'
ok 'snapshot failure refuses PASS and stops later stages'

# Exact provenance + complete live guard cases save four auth selections (32 → 28).
for mode in covered cached helper missing-xml missing-line ambiguous skipped covered-fail malformed partial failed-xml; do
  X="$T/guards-$mode"; pgres::_fixture "$X" "$mode"
  rc=0; run_fixture "$X" --continue-on-failure || rc=$?
  rd="$(run_dir "$X")"
  guard_cmd="$(sed -n '/co.datapipelines.integration.ArchitectureGuardTest/p' "$X/pregate-argv.log")"
  [ -n "$guard_cmd" ] || fail "remaining module guards lost: $mode"
  if [ "$mode" = covered ]; then
    [ "$rc" -eq 0 ] || fail 'fresh covered fixture red'
    [[ "$guard_cmd" != *co.datapipelines.auth.ScopeMatrixSpecDriftTest* ]] || fail 'covered auth guards repeated'
    grep -q 'covered module=:modules:auth.*stage=2 evidence=2/modules/auth/' "$rd/GUARD-COVERAGE.txt" || fail 'covering evidence missing'
    count="$(printf '%s\n' "$guard_cmd" | awk '{for(i=1;i<=NF;i++)if($i=="--tests")n++}END{print n}')"
    [ "$count" -eq 28 ] || fail 'unexpected guard count after covering auth'
    # Compare against an observed baseline recording from the identical fixture.
    B="$T/guards-baseline"; pgres::_fixture "$B" "$mode"
    cat >> "$B/scripts/pregate-results-lib.sh" <<'BASELINE'
pgres::guards_covered() { return 1; }
BASELINE
    run_fixture "$B" || fail 'recording baseline red'
    baseline="$(sed -n '/co.datapipelines.integration.ArchitectureGuardTest/p' "$B/pregate-argv.log" | awk '{for(i=1;i<=NF;i++)if($i=="--tests")n++}END{print n}')"
    [ "$baseline" -eq 32 ] || fail 'baseline inventory not 32'
    echo "  guard selections observed: baseline=$baseline optimized=$count (same stage-2 coverage)"
  else
    [[ "$guard_cmd" == *co.datapipelines.auth.ScopeMatrixSpecDriftTest* ]] || fail "unsafe guard omission: $mode"
    [ ! -e "$rd/GUARD-COVERAGE.txt" ] || fail "unsafe covering evidence: $mode"
  fi
  ok "guard coverage disposition: $mode"
done

# Cached results never qualify, including UP-TO-DATE and all other non-fresh statuses.
X="$T/guards-uptodate"; pgres::_fixture "$X" cached
sed -i 's/test FROM-CACHE/test UP-TO-DATE/' "$X/gradlew"
run_fixture "$X" || fail 'UP-TO-DATE fixture'
grep -q 'co.datapipelines.auth.ScopeMatrixSpecDriftTest' "$X/pregate-argv.log" || fail 'UP-TO-DATE omitted guards'
ok 'UP-TO-DATE retains guards'

# The current integration guards cannot all be covered by product stage 2. Exercise
# the empty-guard executor branch with a deliberately product-only fixture inventory.
X="$T/all-covered"; pgres::_fixture "$X" covered
cat >> "$X/scripts/pregate-plan-lib.sh" <<'INVENTORY'
GUARDS=([":modules:auth"]="co.datapipelines.auth.ScopeMatrixSpecDriftTest co.datapipelines.auth.PublicPathsTest co.datapipelines.auth.RoleMatrixTest co.datapipelines.auth.PermissionResolutionTest")
INVENTORY
run_fixture "$X" || fail 'all-covered fixture'
[ "$(wc -l < "$X/pregate-argv.log")" -eq 4 ] || fail 'all-covered launched empty Gradle'
rd="$(run_dir "$X")"
grep -q '^stage=stage-3 status=covered exit=0 reason=covered-by-stage-2' "$rd/MANIFEST.txt" || fail 'all-covered disposition missing'
grep -q '^stage=3 command=(none)' "$rd/COMMANDS.txt" || fail 'empty actual command missing'
ok 'all-covered stage records coverage and launches no Gradle'

# Function-level clock injection only in copied fixture source: 14 deterministic ticks
# (start, two per stage for the six stages 0 1 2 2b 3 4, finish).
X="$T/timing"; pgres::_fixture "$X"
cat >> "$X/scripts/pregate-results-lib.sh" <<'CLOCK'
pgres::now_ms() {
  local clock_file="$ROOT/.clock-fixture" tick=0
  [ ! -f "$clock_file" ] || tick="$(cat "$clock_file")"
  tick=$((tick + 100)); printf '%s\n' "$tick" > "$clock_file"; printf '%s\n' "$tick"
}
CLOCK
run_fixture "$X" || fail 'timing fixture'
rd="$(run_dir "$X")"
[ "$(grep -c '^timing stage=.* elapsed_ms=100$' "$rd/MANIFEST.txt")" -eq 6 ] || fail 'stage timing accounting'
grep -q '^timing stage=total elapsed_ms=1300$' "$rd/MANIFEST.txt" || fail 'total elapsed accounting'
grep -q '^PRE-GATE PASS.*elapsed_ms=1300' "$X/.pregate-logs/0-verdict.log" || fail 'verdict total timing'
ok 'deterministic stage and total timing includes bookkeeping overhead'

X="$T/final-write-failure"; pgres::_fixture "$X"
cat >> "$X/scripts/pregate-results-lib.sh" <<'FINALWRITE'
pgres::manifest_verdict() { return 1; }
FINALWRITE
rc=0; run_fixture "$X" || rc=$?
[ "$rc" -eq 1 ] || fail 'final manifest failure promoted to PASS'
grep -q '^PRE-GATE FAIL.*snap=1' "$X/.pregate-logs/0-verdict.log" || fail 'final manifest failure not accounted'
ok 'final manifest failure cannot publish PASS'

# Preview under contention must leave prior evidence and the holder record byte-identical.
export XDG_CACHE_HOME="$P/.fixture-cache"
(
  verification::lock preview-holder "$T"
  cp "$XDG_CACHE_HOME/datapipelines/verification-holder.0.txt" "$T/holder-before"
  cp -a "$P/.pregate-logs" "$T/evidence-before"
  cp "$P/pregate-argv.log" "$T/argv-before"
  (cd "$P" && bash scripts/pregate.sh --plan HEAD 9>&- > "$T/locked-preview") || fail 'preview took heavy-work lock'
  cmp "$T/holder-before" "$XDG_CACHE_HOME/datapipelines/verification-holder.0.txt" || fail 'preview overwrote holder'
  [ ! -e "$XDG_CACHE_HOME/datapipelines/verification-holder.1.txt" ] || fail 'preview took a second slot'
  diff -qr "$T/evidence-before" "$P/.pregate-logs" || fail 'preview wrote or pruned evidence'
  cmp "$T/argv-before" "$P/pregate-argv.log" || fail 'preview launched Gradle'
)
ok 'preview during contention preserves evidence, pruning and holder record'

# Dangling symlinks are still symlinks, even though -e reports false.
X="$T/dangling"; mkdir -p "$X"
ln -s "$T/absent-outside-root" "$X/runs"
if pgres::prune_runs "$X" none > "$T/dangling.out" 2>&1; then fail 'dangling runs symlink accepted'; fi
ok 'dangling runs-root symlink is refused'

X="$T/coverage-write-failure"; pgres::_fixture "$X" covered-bookkeeping
rc=0; run_fixture "$X" --continue-on-failure || rc=$?
[ "$rc" -eq 1 ] || fail 'guard accounting failure promoted to PASS'
guard_cmd="$(sed -n '/co.datapipelines.integration.ArchitectureGuardTest/p' "$X/pregate-argv.log")"
[[ "$guard_cmd" == *co.datapipelines.auth.ScopeMatrixSpecDriftTest* ]] || fail 'guard accounting failure still omitted guards'
ok 'failed covering-record write retains guards in diagnostics and refuses PASS'
echo '  pregate efficiency self-test: PASS'

# ---- #797: stage 0, the dependency scan, in both directions --------------------------------
# The scanner is the fixture's recording stand-in (pgres::_write_scan_standin): never the real
# scripts/vuln-scan.sh, never the network. vuln-scan-calls.log is its invocation marker.
verdict() { tail -n 1 "$1/.pregate-logs/0-verdict.log"; }
skip_line='deps-scan: skip — no dependency file changed (gradle.lockfile, gradle/libs.versions.toml, gradle/verification-metadata.xml)'

# (i) no dependency file changed: skipped with its line, scan=0, PASS, the scanner never invoked.
X="$T/deps-none"; pgres::_fixture "$X"
run_fixture "$X" || fail "deps (i): an unchanged dependency set did not PASS: $(tail -n 3 "$X/pregate.out")"
rd="$(run_dir "$X")"
grep -qx 'stage=stage-0 status=skipped exit=0 reason=no-dependency-change' "$rd/MANIFEST.txt" || fail 'deps (i): manifest is not skipped/no-dependency-change'
[[ "$(verdict "$X")" == 'PRE-GATE PASS '*' scan=0 lint=0 '* ]] || fail "deps (i): verdict $(verdict "$X")"
grep -qF '  0 deps-scan skipped — no dependency file changed' "$X/pregate.out" || fail 'deps (i): no explicit skip line'
grep -qxF "$skip_line" "$rd/PLAN.txt" || fail 'deps (i): plan lacks the skip decision'
[ ! -e "$X/vuln-scan-calls.log" ] || fail 'deps (i): the scanner ran with no dependency change'
ok 'deps (i): no dependency change → stage 0 skipped with its line, scan=0, PASS, scanner not invoked'

# (ii) the catalog and a lockfile changed, scan clean: executed once, scan=0, PASS. The trigger
# names BOTH files, so a trigger that ignores either one fails this case by name.
X="$T/deps-clean"; pgres::_fixture "$X"
pgres::_fixture_deps "$X" clean gradle/libs.versions.toml modules/auth/gradle.lockfile
run_fixture "$X" || fail "deps (ii): a clean scan did not PASS: $(tail -n 3 "$X/pregate.out")"
rd="$(run_dir "$X")"
grep -qxF 'deps-scan: run — gradle/libs.versions.toml, modules/auth/gradle.lockfile' "$rd/PLAN.txt" \
  || fail "deps (ii): trigger decision: $(grep '^deps-scan:' "$rd/PLAN.txt")"
grep -qx 'stage=stage-0 status=executed exit=0 reason=completed' "$rd/MANIFEST.txt" || fail 'deps (ii): manifest'
[[ "$(verdict "$X")" == 'PRE-GATE PASS '*' scan=0 lint=0 '* ]] || fail "deps (ii): verdict $(verdict "$X")"
[ "$(argv_lines "$X/vuln-scan-calls.log")" -eq 1 ] || fail 'deps (ii): the scanner did not run exactly once'
[ "$(sed -n 's/^stage=0 command=//p' "$rd/COMMANDS.txt")" = "$(cat "$X/vuln-scan-calls.log")" ] \
  || fail 'deps (ii): recorded and actual scan commands differ'
grep -qF 'No issues found' "$rd/0-deps-scan.log" || fail 'deps (ii): stage log not retained in the run directory'
grep -qF '  0 deps-scan PASS' "$X/pregate.out" || fail 'deps (ii): no PASS line'
[ "$(argv_lines "$X/pregate-argv.log")" -eq 5 ] || fail 'deps (ii): the Gradle stages did not all run after a clean scan'
ok 'deps (ii): catalog + lockfile changed, clean → executed once before lint, scan=0, PASS'

# (iii) known vulnerabilities: scan=1, FAIL, the OSV table in the run directory, nothing after.
X="$T/deps-findings"; pgres::_fixture "$X"
pgres::_fixture_deps "$X" findings modules/auth/gradle.lockfile
rc=0; run_fixture "$X" || rc=$?
[ "$rc" -eq 1 ] || fail "deps (iii): findings exit $rc"
rd="$(run_dir "$X")"
[[ "$(verdict "$X")" == 'PRE-GATE FAIL '*' scan=1 lint=125 mod=125 '* ]] || fail "deps (iii): verdict $(verdict "$X")"
grep -qx 'stage=stage-0 status=executed exit=1 reason=findings' "$rd/MANIFEST.txt" || fail 'deps (iii): manifest'
grep -qF 'GHSA-fixt-ure0-0001' "$rd/0-deps-scan.log" || fail 'deps (iii): the OSV table is not in the run directory stage log'
grep -qF "  0 deps-scan FAIL — known vulnerabilities; the OSV table is in the stage log: $rd/0-deps-scan.log" "$X/pregate.out" \
  || fail 'deps (iii): console does not name the table'
for later in 1 2 2b 3 4; do
  grep -qx "stage=stage-$later status=not-run exit=125 reason=after-0 elapsed_ms=0" "$rd/MANIFEST.txt" || fail "deps (iii): stage $later not marked after 0"
done
[ ! -e "$X/pregate-argv.log" ] || fail 'deps (iii): Gradle launched after a scan FAIL'
ok 'deps (iii): findings → scan=1, FAIL, OSV table in the run directory, later stages not-run after-0'

# (iv) no verdict (scanner error / broken environment): scan=2, FAIL — never a pass.
X="$T/deps-error"; pgres::_fixture "$X"
pgres::_fixture_deps "$X" error gradle/verification-metadata.xml
rc=0; run_fixture "$X" || rc=$?
[ "$rc" -eq 1 ] || fail "deps (iv): error exit $rc"
rd="$(run_dir "$X")"
[[ "$(verdict "$X")" == 'PRE-GATE FAIL '*' scan=2 lint=125 '* ]] || fail "deps (iv): verdict $(verdict "$X")"
grep -qx 'stage=stage-0 status=executed exit=2 reason=no-verdict' "$rd/MANIFEST.txt" || fail 'deps (iv): manifest'
grep -qF '  0 deps-scan FAIL — no verdict: scanner error or broken environment (exit 2; never a pass)' "$X/pregate.out" || fail 'deps (iv): console'
ok 'deps (iv): scanner error → scan=2, FAIL (verification metadata alone triggers)'

# (v) offline: the gate's fail-soft skip — scan=200, skipped/offline, PASS, the WARNING echoed.
X="$T/deps-offline"; pgres::_fixture "$X"
pgres::_fixture_deps "$X" offline gradle/libs.versions.toml
run_fixture "$X" || fail "deps (v): offline did not PASS: $(tail -n 3 "$X/pregate.out")"
rd="$(run_dir "$X")"
[[ "$(verdict "$X")" == 'PRE-GATE PASS '*' scan=200 lint=0 '* ]] || fail "deps (v): verdict $(verdict "$X")"
grep -qx 'stage=stage-0 status=skipped exit=200 reason=offline' "$rd/MANIFEST.txt" || fail 'deps (v): manifest'
grep -qF '  0 deps-scan !! SKIPPED — offline (fail-soft by design; NOT a clean result' "$X/pregate.out" || fail 'deps (v): skip line'
grep -qF '      vuln-scan: WARNING — osv.dev unreachable (fixture stand-in)' "$X/pregate.out" || fail 'deps (v): WARNING not echoed'
[ "$(argv_lines "$X/pregate-argv.log")" -eq 5 ] || fail 'deps (v): an offline skip stopped later stages'
ok 'deps (v): offline → scan=200, skipped reason=offline, PASS, WARNING echoed'

# (vi) --plan prints the trigger decision with and without a dependency change, runs nothing.
X="$T/deps-plan"; pgres::_fixture "$X"
# Look-alikes the anchors must refuse: neither is a dependency file.
printf 'x\n' > "$X/gradle/libs.versions.toml.orig"; printf 'x\n' > "$X/modules/auth/old.gradle.lockfile"
(cd "$X" && bash scripts/pregate.sh --plan HEAD) > "$T/deps-plan-none" || fail 'deps (vi): plan without a change'
grep -qxF "$skip_line" "$T/deps-plan-none" || fail "deps (vi): look-alikes triggered: $(grep '^deps-scan:' "$T/deps-plan-none")"
grep -qx 'stage=0 command=(none)' "$T/deps-plan-none" || fail 'deps (vi): planned a scan with no change'
pgres::_fixture_deps "$X" findings gradle/libs.versions.toml
(cd "$X" && bash scripts/pregate.sh --plan HEAD) > "$T/deps-plan-catalog" || fail 'deps (vi): plan with a change'
grep -qxF 'deps-scan: run — gradle/libs.versions.toml' "$T/deps-plan-catalog" \
  || fail "deps (vi): catalog trigger: $(grep '^deps-scan:' "$T/deps-plan-catalog")"
grep -qx 'stage order: 0 1 2 2b 3 4' "$T/deps-plan-catalog" || fail 'deps (vi): stage order'
grep -qx 'stage=0 command=./scripts/vuln-scan.sh ' "$T/deps-plan-catalog" || fail 'deps (vi): planned scan command'
[ ! -e "$X/vuln-scan-calls.log" ] && [ ! -e "$X/.pregate-logs" ] && [ ! -e "$X/pregate-argv.log" ] || fail 'deps (vi): --plan ran something'
ok 'deps (vi): --plan names the trigger decision both ways and runs nothing; look-alike paths do not trigger'
echo '  dependency-scan stage self-test: PASS'

# ---- #773: a failed stage names its crashed test JVM's HotSpot report ------------------------
crash_free() { # the output carries none of the crash lines
  ! grep -qE 'CRASHED|crash report|older reports exist|no HotSpot report' "$1"
}

# 134: HotSpot's handler wrote the report; the stage output names module, path, both lines.
X="$T/crash-134"; pgres::_fixture "$X" crash
rc=0; run_fixture "$X" || rc=$?
[ "$rc" -eq 1 ] || fail "crash (134): exit $rc"
rd="$(run_dir "$X")"
grep -qF '    a test worker CRASHED (exit value 134) — not a test failure (see DEVELOPMENT.md §9.4)' "$X/pregate.out" || fail 'crash (134): CRASHED sentence'
grep -qF '    crash report  module=auth  path=modules/auth/build/hs_err/hs_err_pid4242.log' "$X/pregate.out" || fail 'crash (134): module and path'
grep -qF '      #  SIGSEGV (0xb) at pc=0x00007f3a1c2d3e4f, pid=4242, tid=4243' "$X/pregate.out" || fail 'crash (134): signal line'
grep -qF '      # C  [libduckdb_java.so+0x1234]' "$X/pregate.out" || fail 'crash (134): frame line'
! grep -q 'FIXTURE_ENV_SENTINEL' "$X/pregate.out" || fail 'crash (134): printed the report beyond its bounded lines'
! grep -q 'no HotSpot report' "$X/pregate.out" || fail 'crash (134): claimed no report'
grep -qF 'path=modules/auth/build/hs_err/hs_err_pid4242.log' "$rd/stage-2.crash-reports.txt" || fail 'crash (134): run directory lacks the crash lines'
[[ "$(verdict "$X")" == 'PRE-GATE FAIL '*' mod=1 '* ]] || fail "crash (134): verdict $(verdict "$X")"
ok 'crash (134): the failure names module auth, the report path, its signal and frame lines, and CRASHED'

# 139: an unhandled SIGSEGV leaves no report, and the stage says so.
X="$T/crash-139"; pgres::_fixture "$X" crash-139
rc=0; run_fixture "$X" || rc=$?
[ "$rc" -eq 1 ] || fail "crash (139): exit $rc"
grep -qF '    a test worker CRASHED (exit value 139) — not a test failure' "$X/pregate.out" || fail 'crash (139): CRASHED sentence'
grep -qF '    no HotSpot report — an unhandled SIGSEGV (exit 139) leaves none; DEVELOPMENT.md §9.4' "$X/pregate.out" || fail 'crash (139): §9.4 sentence'
! grep -q 'crash report  module=' "$X/pregate.out" || fail 'crash (139): attributed a report'
ok 'crash (139): no report → the §9.4 sentence'

# A report older than the step is counted, never attributed, never deleted.
X="$T/crash-stale"; pgres::_fixture "$X" fail
stale="$X/modules/auth/build/hs_err/hs_err_pid1111.log"
mkdir -p "$(dirname "$stale")"
printf '#  SIGSEGV (0xb) at pc=0x1, pid=1111, tid=1112\n# Problematic frame:\n# C  [libstale.so+0x1]\n' > "$stale"
touch -d '2026-01-01 00:00:00' "$stale"
rc=0; run_fixture "$X" || rc=$?
[ "$rc" -eq 1 ] || fail "crash (stale): exit $rc"
grep -qF '    older reports exist, not from this step: 1' "$X/pregate.out" || fail 'crash (stale): older count'
! grep -qE 'hs_err_pid1111|libstale|CRASHED|no HotSpot report' "$X/pregate.out" || fail 'crash (stale): the earlier report was attributed'
[ -f "$stale" ] || fail 'crash (stale): the earlier report was deleted'
ok 'crash (stale): a report from before the step is counted, not attributed, and kept'

# A genuine failure and a green run with no report print nothing extra.
X="$T/crash-none"; pgres::_fixture "$X" fail
rc=0; run_fixture "$X" || rc=$?
[ "$rc" -eq 1 ] && crash_free "$X/pregate.out" || fail 'crash (none): a plain test failure printed crash lines'
rd="$(run_dir "$X")"; [ ! -e "$rd/stage-2.crash-reports.txt" ] || fail 'crash (none): wrote a crash record'
crash_free "$T/deps-none/pregate.out" || fail 'crash (none): a green run printed crash lines'
ok 'crash (none): a failure without a report, and a green run, print nothing extra'

# The helper itself (the one definition gate.sh calls too): an EQUAL mtime is the step's —
# find -newer is strict and drops a report written in the marker's clock tick; tests/* modules
# are searched; a symlinked report is never read; a missing marker attributes nothing.
source "$ROOT/scripts/lib/gate-stages.sh"
H="$T/crash-helper"; mkdir -p "$H/modules/web/build/hs_err" "$H/tests/integration-tests/build/hs_err"
: > "$H/start"
printf '#  Internal Error (fixture.cpp:1), pid=7, tid=8\n# Problematic frame:\n# V  [libjvm.so+0x7]\n' > "$H/tests/integration-tests/build/hs_err/hs_err_pid7.log"
touch -r "$H/start" "$H/tests/integration-tests/build/hs_err/hs_err_pid7.log"
printf '#  SIGBUS (0x7) at pc=0x2\nLINKED_REPORT_SENTINEL\n' > "$T/outside-report.log"
ln -s "$T/outside-report.log" "$H/modules/web/build/hs_err/hs_err_pid9.log"
helper_out="$(gate_crash_reports "$H/start" "$H")" || fail 'helper: an equal-mtime report was not attributed'
[ -z "$(find "$H/tests/integration-tests/build/hs_err" -newer "$H/start")" ] || fail 'helper: the equal-mtime premise did not hold'
[[ "$helper_out" == *'module=integration-tests  path=tests/integration-tests/build/hs_err/hs_err_pid7.log'* ]] || fail "helper: tests/* report: $helper_out"
[[ "$helper_out" == *'#  Internal Error (fixture.cpp:1), pid=7, tid=8'* && "$helper_out" == *'# V  [libjvm.so+0x7]'* ]] || fail 'helper: Internal Error lines'
[[ "$helper_out" != *SIGBUS* && "$helper_out" != *hs_err_pid9* ]] || fail 'helper: followed a symlinked report'
rc=0; helper_out="$(gate_crash_reports "$H/no-such-marker" "$H")" || rc=$?
[ "$rc" -eq 1 ] && [[ "$helper_out" == *'start marker'*'missing — no report attributed'* ]] || fail 'helper: a missing marker attributed reports'
ok 'crash helper: equal mtime attributed (find -newer would not), tests/* searched, symlinks and missing markers refused'
echo '  crash-report self-test: PASS'
