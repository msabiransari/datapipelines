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
  if args="$(pg2b::gradle_args browser-tests $plan 2>/dev/null)"; then
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
    bash "$script" 9>&- > "$T/refused" 2>&1 || rc=$?
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
