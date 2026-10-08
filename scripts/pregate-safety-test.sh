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
done < "$F/pregate-argv.log"
ok 'real pregate defers browser coverage and limits every Gradle invocation'

# Separate checkouts, same user's cache: both wrappers must refuse, without
# deleting prior gate evidence or reaching the recording stand-in.
G="$T/other-checkout"
mkdir -p "$G/scripts/lib" "$G/.gate-logs"
cp "$ROOT/scripts/gate.sh" "$G/scripts/"
cp "$ROOT/scripts/lib/verification-lock.sh" "$G/scripts/lib/"
printf 'prior gate evidence\n' > "$G/.gate-logs/sentinel"
export XDG_CACHE_HOME="$F/.fixture-cache"
source "$ROOT/scripts/lib/verification-lock.sh"
before="$(cat "$F/pregate-argv.log")"
(
  verification::lock fixture "$T"
  for script in "$F/scripts/pregate.sh" "$G/scripts/gate.sh"; do
    rc=0
    # Close the inherited holder fd in the contender, exactly like an independent session.
    wrapper_args=()
    [[ "$script" != */pregate.sh ]] || wrapper_args=(HEAD)
    bash "$script" "${wrapper_args[@]}" 9>&- > "$T/refused" 2>&1 || rc=$?
    [ "$rc" -eq 75 ] || fail "overlap was not refused: $script ($rc)"
    grep -q "kind=fixture.*cwd=$T" "$T/refused" || fail 'holder not named'
  done
)
[ "$before" = "$(cat "$F/pregate-argv.log")" ] || fail 'busy pregate launched Gradle'
[ "$(cat "$G/.gate-logs/sentinel")" = 'prior gate evidence' ] || fail 'busy gate erased evidence'
(verification::lock after-exit "$T") || fail 'lock was not released after exit'
pgres::_run_fixture "$F" || fail 'pregate could not run after holder exited'
ok 'cross-checkout overlap refused before work; lock released on exit'
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
sed -n 's/^stage=[^ ]* command=//p' "$T/preview" > "$T/planned"
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

for stage in 1 2 2b 3 4; do
  X="$T/failure-$stage"; pgres::_fixture "$X" "fail-$stage"
  rc=0; run_fixture "$X" || rc=$?
  [ "$rc" -eq 1 ] || fail "failure-$stage did not FAIL"
  rd="$(run_dir "$X")"
  expected=0; seen=0
  for later in 1 2 2b 3 4; do
    expected=$((expected + 1))
    if [ "$later" = "$stage" ]; then seen=1; break; fi
  done
  [ "$seen" -eq 1 ] || fail 'fixture stage missing'
  [ "$(wc -l < "$X/pregate-argv.log")" -eq "$expected" ] || fail "fail-fast scheduled after $stage"
  for later in 1 2 2b 3 4; do
    if [ "$seen" -eq 2 ]; then
      grep -q "stage=stage-$later status=not-run exit=125 reason=after-$stage" "$rd/MANIFEST.txt" || fail "later stage $later not marked after $stage"
    fi
    [ "$later" != "$stage" ] || seen=2
  done
  grep -q '^PRE-GATE FAIL' "$X/.pregate-logs/0-verdict.log" || fail 'failed verdict missing'
  test -f "$rd/$(case "$stage" in 1) echo 1-lint.log;; 2) echo 2-touched-modules.log;; 2b) echo 2b-tests-modules.log;; 3) echo 3-guards.log;; 4) echo 4-compile-all-tests.log;; esac)" || fail 'failed log not retained'
  grep -q "stage=stage-$stage status=executed exit=8" "$rd/MANIFEST.txt" || fail 'original stage exit lost'
  ok "failure at stage $stage preserves evidence and stops scheduling"
  D="$T/diagnostic-$stage"; pgres::_fixture "$D" "fail-$stage"
  rc=0; run_fixture "$D" --continue-on-failure || rc=$?
  [ "$rc" -eq 1 ] && [ "$(wc -l < "$D/pregate-argv.log")" -eq 5 ] || fail "diagnostic continuation after $stage"
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

# Exact provenance + complete live guard cases save four auth selections (31 → 27).
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
    [ "$count" -eq 27 ] || fail 'unexpected guard count after covering auth'
    # Compare against an observed baseline recording from the identical fixture.
    B="$T/guards-baseline"; pgres::_fixture "$B" "$mode"
    cat >> "$B/scripts/pregate-results-lib.sh" <<'BASELINE'
pgres::guards_covered() { return 1; }
BASELINE
    run_fixture "$B" || fail 'recording baseline red'
    baseline="$(sed -n '/co.datapipelines.integration.ArchitectureGuardTest/p' "$B/pregate-argv.log" | awk '{for(i=1;i<=NF;i++)if($i=="--tests")n++}END{print n}')"
    [ "$baseline" -eq 31 ] || fail 'baseline inventory not 31'
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

# Function-level clock injection only in copied fixture source: 12 deterministic ticks.
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
[ "$(grep -c '^timing stage=.* elapsed_ms=100$' "$rd/MANIFEST.txt")" -eq 5 ] || fail 'stage timing accounting'
grep -q '^timing stage=total elapsed_ms=1100$' "$rd/MANIFEST.txt" || fail 'total elapsed accounting'
grep -q '^PRE-GATE PASS.*elapsed_ms=1100' "$X/.pregate-logs/0-verdict.log" || fail 'verdict total timing'
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
  cp "$XDG_CACHE_HOME/datapipelines/verification-holder.txt" "$T/holder-before"
  cp -a "$P/.pregate-logs" "$T/evidence-before"
  cp "$P/pregate-argv.log" "$T/argv-before"
  (cd "$P" && bash scripts/pregate.sh --plan HEAD 9>&- > "$T/locked-preview") || fail 'preview took heavy-work lock'
  cmp "$T/holder-before" "$XDG_CACHE_HOME/datapipelines/verification-holder.txt" || fail 'preview overwrote holder'
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
