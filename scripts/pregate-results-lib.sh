# shellcheck shell=bash
#
# pregate-results-lib.sh — per-stage JUnit evidence preservation for scripts/pregate.sh (#441).
#
# WHY THIS LIBRARY EXISTS
# -----------------------
# The pre-gate runs stage 2 (the touched modules' UNFILTERED check) and then stage 3
# (the cross-cutting guards, FILTERED in overlapping modules). Both write to the SAME
# `build/test-results/<task>/` directory, and Gradle deletes a test task's result
# directory before it runs: after stage 3, a module's stage-2 XML is GONE. A lane's
# post-pregate recount therefore sees only the guards and silently under-reports the
# stage that ran the bulk of the tests (#441; the gate twin is #345, which gave the
# security-assurance stage its own task and directory).
#
# WHAT IT DOES
# ------------
# Every pregate invocation gets its own run directory under `.pregate-logs/runs/`:
#
#   .pregate-logs/runs/<run-id>/
#     MANIFEST.txt                 base/merge-base/HEAD, timestamp, per-stage status/exit
#     2/modules/<m>/build/test-results/<task>/TEST-*.xml
#     2b/tests/<m>/build/test-results/<task>/TEST-*.xml
#     3/modules/<m>/build/test-results/<task>/TEST-*.xml
#     3/tests/<m>/build/test-results/<task>/TEST-*.xml
#     <stage>/PROVENANCE.txt       the stage log's test-task status lines (UP-TO-DATE…)
#
# Each test-producing stage (2, 2b, 3) is snapshotted IMMEDIATELY after it returns,
# before the next stage can overwrite its results, and EVEN when the stage failed (the
# failing XML is the evidence). A copy, provenance or manifest write failure refuses a
# PASS — it never turns a Gradle failure into success, and the original stage exits stay
# in the verdict line. A skipped stage is recorded as skipped, not as an empty pass.
#
# READ ONE STAGE AT A TIME: `./scripts/test-recount.sh .pregate-logs/runs/<run-id>/2`.
# Guard classes can legitimately run in more than one stage, so stages are NEVER summed
# into a purported unique-test total.
#
# SECURITY: snapshots stay under this worktree's `.pregate-logs`, mirror only the
# repo-relative paths of the `modules/<m>` and `tests/<m>` roots the stage ran, and walk
# no symlinked or external path. Test fixtures that stand in for Gradle record their argv
# and REFUSE anything they did not fake — never a fallthrough to the real binary.
#
# Entry points (all pure over the filesystem except the self-test's git fixture):
#   pgres::new_run_id <logdir>                    — unique, filesystem-safe run id
#   pgres::run_dir <logdir> <run-id>              — the run directory path
#   pgres::allowed_root <rel>                     — exit 0 iff rel is modules/<m> or tests/<m>
#   pgres::snapshot <root> <stage-dir> <log> <roots...>  — copy XML, print file count
#   pgres::write_provenance <log> <dest>          — record test-task status lines
#   pgres::manifest_init <manifest> <run-id> <base> <mb> <head>
#   pgres::manifest_stage <manifest> <stage> <status> <exit> <desc>
#   pgres::manifest_verdict <manifest> <verdict> <run-id>
#   pgres::capture <root> <run-dir> <stage> <status> <exit> <log> [roots...]
#   pgres::self_test                              — isolated fixtures + refusing stand-in; 0 = PASS

PGRES_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# A unique, filesystem-safe run id. The directory must not already exist, so a rerun can
# never present the previous run's XML as its own: prior runs stay separate and the
# caller snapshots only what THIS run produced.
pgres::new_run_id() {
  local logdir="$1"
  local stamp short base id n
  stamp="$(date -u '+%Y%m%dT%H%M%SZ')"
  short="$(git rev-parse --short HEAD 2>/dev/null || echo nogit)"
  base="${stamp}-${short}-$$"
  id="$base"; n=1
  while [ -e "$logdir/runs/$id" ]; do
    n=$((n + 1)); id="${base}-${n}"
  done
  printf '%s' "$id"
}

pgres::run_dir() { printf '%s/runs/%s' "$1" "$2"; }

# A snapshot root is exactly `modules/<name>` or `tests/<name>`, `<name>` a plain
# lowercase directory name. Refuses absolute paths, `..`, `.claude`, sibling worktrees
# and every other traversal before a single file is read.
pgres::allowed_root() {
  case "$1" in
    modules/*|tests/*) ;;
    *) return 1 ;;
  esac
  local name="${1#*/}"
  case "$name" in
    ''|*[!a-z0-9-]*) return 1 ;;
  esac
  return 0
}

# pgres::snapshot <root> <stage-dir> <log> <module-root...>
# Copies every *.xml under each module root's build/test-results/ into stage-dir,
# mirroring the repo-relative path, and writes the stage's PROVENANCE.txt. Prints the
# copied file count. Returns non-zero if a root is invalid, a directory cannot be made,
# a copy fails, or provenance cannot be written.
pgres::snapshot() {
  local root="$1" stage_dir="$2" log="$3"
  shift 3
  mkdir -p "$stage_dir" || { echo "pgres::snapshot: cannot create '$stage_dir'" >&2; return 1; }
  local rel f dst copied=0
  for rel in "$@"; do
    if ! pgres::allowed_root "$rel"; then
      echo "pgres::snapshot: refusing root '$rel' (not modules/<m> or tests/<m>)" >&2
      return 1
    fi
    [ -d "$root/$rel/build/test-results" ] || continue
    while IFS= read -r -d '' f; do
      dst="$stage_dir/${f#"$root/"}"
      mkdir -p "$(dirname "$dst")" || { echo "pgres::snapshot: cannot create '$(dirname "$dst")'" >&2; return 1; }
      cp -p "$f" "$dst" || { echo "pgres::snapshot: cannot copy '$f'" >&2; return 1; }
      copied=$((copied + 1))
    done < <(find "$root/$rel/build/test-results" -type f -name '*.xml' -print0 2>/dev/null)
  done
  pgres::write_provenance "$log" "$stage_dir/PROVENANCE.txt" || {
    echo "pgres::snapshot: cannot write provenance for '$stage_dir'" >&2; return 1; }
  printf '%s' "$copied"
}

# Records how Gradle reported each test task in the stage's log — the honest provenance
# of the copied XML. A task marked UP-TO-DATE or FROM-CACHE means the file is a previous
# execution's result, not fresh this run; the reader must not claim fresh execution from
# a file's existence alone.
pgres::write_provenance() {
  local log="$1" dest="$2"
  {
    printf '# provenance for %s — test-task status lines from the stage log\n' "$(basename "$(dirname "$dest")")"
    if [ -f "$log" ]; then
      grep -E '^> Task :(modules|tests):[a-z-]+:(test|breachSuite|editorJsTest|securityAssuranceTest)( [A-Z-]+)?' "$log" \
        | sort -u || true
    else
      printf '# (stage log %s is absent)\n' "$log"
    fi
  } > "$dest" || return 1
}

pgres::manifest_init() {
  local mf="$1" run_id="$2" base="$3" mb="$4" head="$5"
  local ts; ts="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  mkdir -p "$(dirname "$mf")" || return 1
  {
    printf 'run-id=%s\n' "$run_id"
    printf 'base=%s\n' "$base"
    printf 'merge-base=%s\n' "$mb"
    printf 'head=%s\n' "$head"
    printf 'timestamp=%s\n' "$ts"
  } > "$mf" || return 1
}

pgres::manifest_stage() {
  local mf="$1" stage="$2" status="$3" exit="$4" desc="$5"
  printf 'stage=%s status=%s exit=%s %s\n' "$stage" "$status" "$exit" "$desc" >> "$mf" || return 1
}

pgres::manifest_verdict() {
  local mf="$1" verdict="$2" run_id="$3"
  printf 'verdict=%s run=%s\n' "$verdict" "$run_id" >> "$mf" || return 1
}

# pgres::capture <root> <run-dir> <stage> <status> <stage-exit> <log> [module-root...]
# Snapshots one stage and appends its manifest line. `status` is executed or skipped.
# Returns non-zero on any snapshot/manifest failure so pregate.sh refuses a PASS.
pgres::capture() {
  local root="$1" run_dir="$2" stage="$3" status="$4" stage_exit="$5" log="$6"
  shift 6
  local sdir="$run_dir/$stage" mf="$run_dir/MANIFEST.txt" files counts first rc=0
  mkdir -p "$run_dir" || { echo "pgres::capture: cannot create '$run_dir'" >&2; return 1; }
  if [ "$status" = skipped ]; then
    mkdir -p "$sdir" || return 1
    pgres::manifest_stage "$mf" "stage-$stage" skipped "$stage_exit" "modules=none" || return 1
    return 0
  fi
  if ! files="$(pgres::snapshot "$root" "$sdir" "$log" "$@")"; then
    pgres::manifest_stage "$mf" "stage-$stage" "$status" "$stage_exit" "SNAPSHOT-FAILED" || true
    echo "pgres::capture: snapshot failed for stage $stage" >&2
    return 1
  fi
  counts="$(bash "$root/scripts/test-recount.sh" "$sdir" 2>&1)" || rc=$?
  first="$(printf '%s\n' "$counts" | head -n 1)"
  pgres::manifest_stage "$mf" "stage-$stage" "$status" "$stage_exit" \
    "snapshot_files=$files $first provenance=$([ -f "$sdir/PROVENANCE.txt" ] && echo yes || echo no)" || return 1
  [ "$rc" -eq 0 ] || return 1
  return 0
}

# ---------------------------------------------------------------------------
# Self-test: isolated fixtures, a refusing Gradle stand-in, and the REAL
# scripts/pregate.sh orchestration run inside a throwaway checkout.
# ---------------------------------------------------------------------------

# Writes the refusing stand-in at <fixture>/gradlew. It records every argv, answers only
# the invocations the fixtures fake, and REFUSES anything else with exit 64 — never a
# fallthrough to a real Gradle. Mode comes from <fixture>/.standin-mode (happy|fail).
pgres::_write_standin() {
  local F="$1"
  cat > "$F/gradlew" <<'STANDIN'
#!/usr/bin/env bash
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
printf '%s\n' "$*" >> "$ROOT/pregate-argv.log"
args="$*"
mode="$(cat "$ROOT/.standin-mode" 2>/dev/null || echo happy)"
write_tests() { # write_tests <path> <name> <tests> <failures>
  mkdir -p "$(dirname "$1")"
  cat > "$1" <<XML
<testsuite name="$2" tests="$3" failures="$4" errors="0" skipped="0"></testsuite>
XML
}
case "$args" in
  *ktlintCheck*) exit 0 ;;
  *compileTestKotlin*) exit 0 ;;
  *:modules:auth:check*)
    write_tests "$ROOT/modules/auth/build/test-results/test/TEST-co.x.StageTwoTest.xml" co.x.StageTwoTest 3 "$([ "$mode" = fail ] && echo 1 || echo 0)"
    write_tests "$ROOT/modules/scripting/build/test-results/breachSuite/TEST-co.x.BreachTest.xml" co.x.BreachTest 1 0
    if [ -e "$ROOT/.runone" ]; then
      write_tests "$ROOT/modules/auth/build/test-results/test/TEST-co.x.RunOneOnly.xml" co.x.RunOneOnly 1 0
      rm -f "$ROOT/.runone"
    fi
    [ "$mode" = fail ] && exit 1
    exit 0 ;;
  *co.x.ChangedE2eTest*)
    rm -rf "$ROOT/tests/integration-tests/build/test-results/test"
    write_tests "$ROOT/tests/integration-tests/build/test-results/test/TEST-co.x.ChangedE2eTest.xml" co.x.ChangedE2eTest 4 0
    exit 0 ;;
  *co.datapipelines.integration.*)
    rm -rf "$ROOT/modules/auth/build/test-results/test"
    write_tests "$ROOT/modules/auth/build/test-results/test/TEST-co.x.GuardTest.xml" co.x.GuardTest 2 0
    rm -rf "$ROOT/tests/integration-tests/build/test-results/test"
    write_tests "$ROOT/tests/integration-tests/build/test-results/test/TEST-co.datapipelines.integration.GuardE2eTest.xml" co.datapipelines.integration.GuardE2eTest 2 0
    exit 0 ;;
esac
echo "stand-in refused: $args" >> "$ROOT/pregate-argv.log"
exit 64
STANDIN
  chmod +x "$F/gradlew"
}

# Builds a throwaway git checkout whose diff touches modules/auth, modules/scripting and
# a runnable tests/integration-tests class, and stages copies of the REAL scripts.
pgres::_fixture() {
  local F="$1" mode="${2:-happy}"
  mkdir -p "$F/scripts" \
           "$F/modules/auth/src/main/kotlin/co/x" \
           "$F/modules/scripting/src/main/kotlin/co/x" \
           "$F/tests/integration-tests/src/test/kotlin/co/x" \
           "$F/buildSrc/src/main/kotlin"
  cp "$PGRES_LIB_DIR/pregate.sh" "$PGRES_LIB_DIR/pregate-results-lib.sh" \
     "$PGRES_LIB_DIR/pregate-2b-lib.sh" "$PGRES_LIB_DIR/test-recount.sh" "$F/scripts/"
  printf 'build/\n.pregate-logs/\npregate-argv.log\npregate.out\n' > "$F/.gitignore"
  echo '// auth source' > "$F/modules/auth/src/main/kotlin/co/x/Auth.kt"
  echo '// scripting source' > "$F/modules/scripting/src/main/kotlin/co/x/Script.kt"
  echo '// auth build' > "$F/modules/auth/build.gradle.kts"
  echo '// scripting build' > "$F/modules/scripting/build.gradle.kts"
  echo '// integration build' > "$F/tests/integration-tests/build.gradle.kts"
  cat > "$F/buildSrc/src/main/kotlin/CommonConventionsPlugin.kt" <<'EOF'
val COVERAGE_FLOORS = mapOf(":modules:auth" to 90, ":modules:scripting" to 90)
EOF
  printf '%s\n' "$mode" > "$F/.standin-mode"
  pgres::_write_standin "$F"
  (
    cd "$F" || exit 2
    git init -q
    git config user.email lane@example.test
    git config user.name lane
    git add -A
    git commit -qm base
  ) || return 1
  {
    echo '// touched'
  } >> "$F/modules/auth/src/main/kotlin/co/x/Auth.kt"
  {
    echo '// touched'
  } >> "$F/modules/scripting/src/main/kotlin/co/x/Script.kt"
  cat > "$F/tests/integration-tests/src/test/kotlin/co/x/ChangedE2eTest.kt" <<'EOF'
package co.x

class ChangedE2eTest {
    @org.junit.jupiter.api.Test
    fun c() {}
}
EOF
  return 0
}

# Runs the REAL scripts/pregate.sh inside the fixture (base = HEAD, the working-tree diff
# is the change under test). Returns pregate's exit code.
pgres::_run_fixture() {
  local F="$1"
  ( cd "$F" && bash scripts/pregate.sh HEAD > pregate.out 2>&1 )
}

pgres::self_test() {
  local T
  T="$(mktemp -d /tmp/pgres-selftest.XXXXXX)"
  trap "rm -rf '$T'" EXIT
  local fails=0

  ck_eq() { # ck_eq <name> <expected> <got>
    local name="$1" exp="$2" got="$3"
    if [ "$exp" = "$got" ]; then echo "  ok   $name"
    else echo "  FAIL $name"; echo "       expected: $exp"; echo "       got:      $got"; fails=$((fails + 1)); fi
  }
  ck_ok() { # ck_ok <name> <command...>
    local name="$1"; shift
    if "$@" >/dev/null 2>&1; then echo "  ok   $name"
    else echo "  FAIL $name"; fails=$((fails + 1)); fi
  }
  ck_contains() { # ck_contains <name> <haystack> <needle>
    local name="$1" hay="$2" needle="$3"
    if printf '%s' "$hay" | grep -qF -- "$needle"; then echo "  ok   $name"
    else echo "  FAIL $name"; echo "       missing: $needle"; fails=$((fails + 1)); fi
  }
  first_run() { ls -1 "$1/.pregate-logs/runs" 2>/dev/null | sort | head -n 1; }

  # ---- 1. happy fixture: stage 2 and 2b survive stage 3's overwrite -------------
  local F="$T/happy" rd run_id c2 c2b c3
  ck_ok "fixture-built" pgres::_fixture "$F" happy
  ck_ok "happy-pregate-green" pgres::_run_fixture "$F"
  rd="$F/.pregate-logs/runs/$(first_run "$F")"
  run_id="$(basename "$rd")"
  ck_ok "stage2-report-present" test -f "$rd/2/modules/auth/build/test-results/test/TEST-co.x.StageTwoTest.xml"
  ck_ok "stage2-breach-task-dir-present" test -f "$rd/2/modules/scripting/build/test-results/breachSuite/TEST-co.x.BreachTest.xml"
  ck_ok "stage2b-report-present" test -f "$rd/2b/tests/integration-tests/build/test-results/test/TEST-co.x.ChangedE2eTest.xml"
  ck_ok "stage3-guard-present" test -f "$rd/3/modules/auth/build/test-results/test/TEST-co.x.GuardTest.xml"
  ck_ok "stage3-overwrote-stage2" test ! -f "$rd/3/modules/auth/build/test-results/test/TEST-co.x.StageTwoTest.xml"
  ck_ok "stage3-overwrote-stage2b" test ! -f "$rd/3/tests/integration-tests/build/test-results/test/TEST-co.x.ChangedE2eTest.xml"
  c2="$(bash "$F/scripts/test-recount.sh" "$rd/2" | head -n 1)"
  ck_eq "stage2-counts" "files=2 tests=4 failures=0 errors=0 skipped=0 unreadable=0" "$c2"
  c2b="$(bash "$F/scripts/test-recount.sh" "$rd/2b" | head -n 1)"
  ck_eq "stage2b-counts" "files=1 tests=4 failures=0 errors=0 skipped=0 unreadable=0" "$c2b"
  c3="$(bash "$F/scripts/test-recount.sh" "$rd/3" | head -n 1)"
  ck_eq "stage3-counts" "files=2 tests=4 failures=0 errors=0 skipped=0 unreadable=0" "$c3"
  ck_ok "verdict-pass" grep -q 'PRE-GATE PASS' "$F/.pregate-logs/0-verdict.log"
  ck_ok "verdict-run-id" grep -q "run=$run_id" "$F/.pregate-logs/0-verdict.log"
  ck_ok "verdict-snap-zero" grep -q 'snap=0' "$F/.pregate-logs/0-verdict.log"
  ck_ok "manifest-stage2-executed" grep -q '^stage=stage-2 status=executed exit=0 ' "$rd/MANIFEST.txt"
  ck_ok "manifest-stage2b-executed" grep -q '^stage=stage-2b status=executed exit=0 ' "$rd/MANIFEST.txt"
  ck_ok "manifest-stage3-executed" grep -q '^stage=stage-3 status=executed exit=0 ' "$rd/MANIFEST.txt"
  ck_ok "manifest-has-base-head-time" grep -q '^head=' "$rd/MANIFEST.txt"
  ck_ok "provenance-written" test -f "$rd/2/PROVENANCE.txt"
  ck_eq "manifest-final-verdict" "verdict=PASS run=$run_id" "$(tail -n 1 "$rd/MANIFEST.txt")"

  # ---- 2. a failing stage 2 keeps its failing XML even though stage 3 is green --
  local FF frd fc
  FF="$T/fail"
  ck_ok "fail-fixture-built" pgres::_fixture "$FF" fail
  if pgres::_run_fixture "$FF"; then
    echo "  FAIL fail-pregate-red (exit was 0)"; fails=$((fails + 1))
  else
    echo "  ok   fail-pregate-red"
  fi
  frd="$FF/.pregate-logs/runs/$(first_run "$FF")"
  ck_ok "fail-verdict" grep -q 'PRE-GATE FAIL' "$FF/.pregate-logs/0-verdict.log"
  ck_ok "fail-verdict-keeps-mod-exit" grep -q 'mod=1' "$FF/.pregate-logs/0-verdict.log"
  ck_ok "fail-stage2-xml-survives" test -f "$frd/2/modules/auth/build/test-results/test/TEST-co.x.StageTwoTest.xml"
  fc="$(bash "$FF/scripts/test-recount.sh" "$frd/2" | head -n 1)"
  ck_eq "fail-stage2-counts-retain-failure" "files=2 tests=4 failures=1 errors=0 skipped=0 unreadable=0" "$fc"
  ck_ok "fail-stage3-was-green" grep -q '^stage=stage-3 status=executed exit=0 ' "$frd/MANIFEST.txt"

  # ---- 3. a second invocation cannot reuse the previous run's results ----------
  local R r1 r2 rid1 rid2
  R="$T/reuse"
  ck_ok "reuse-fixture-built" pgres::_fixture "$R" happy
  : > "$R/.runone"
  ck_ok "reuse-run1-green" pgres::_run_fixture "$R"
  r1="$R/.pregate-logs/runs/$(first_run "$R")"
  rid1="$(basename "$r1")"
  ck_ok "run1-wrote-runone-only" test -f "$r1/2/modules/auth/build/test-results/test/TEST-co.x.RunOneOnly.xml"
  ck_ok "reuse-run2-green" pgres::_run_fixture "$R"
  r2="$(ls -1 "$R/.pregate-logs/runs" | sort | tail -n 1)"; r2="$R/.pregate-logs/runs/$r2"
  rid2="$(basename "$r2")"
  ck_ok "run-ids-differ" test "$rid1" != "$rid2"
  ck_ok "run2-cannot-see-run1-xml" test ! -f "$r2/2/modules/auth/build/test-results/test/TEST-co.x.RunOneOnly.xml"

  # ---- 3b. a snapshot-write failure refuses the verdict even with green stages --
  local B
  B="$T/blocked"
  ck_ok "blocked-fixture-built" pgres::_fixture "$B" happy
  mkdir -p "$B/.pregate-logs"; : > "$B/.pregate-logs/runs"
  if pgres::_run_fixture "$B"; then
    echo "  FAIL blocked-pregate-red (exit was 0)"; fails=$((fails + 1))
  else
    echo "  ok   blocked-pregate-red"
  fi
  ck_ok "blocked-verdict-fail" grep -q 'PRE-GATE FAIL' "$B/.pregate-logs/0-verdict.log"
  ck_ok "blocked-verdict-snap1" grep -q 'snap=1' "$B/.pregate-logs/0-verdict.log"
  ck_ok "blocked-stages-were-green" grep -q 'lint=0 mod=0' "$B/.pregate-logs/0-verdict.log"

  # ---- 4. library boundary cases: malformed, missing root, missing output,
  #         capture-write failure, skipped stage ------------------------------
  local md mout mr out
  md="$T/malformed"; mkdir -p "$md/modules/auth/build/test-results/test"
  printf '<testsuite' > "$md/modules/auth/build/test-results/test/TEST-bad.xml"
  mout="$(bash "$PGRES_LIB_DIR/test-recount.sh" "$md" 2>&1)"
  ck_contains "malformed-counted" "$mout" 'unreadable=1'
  ck_contains "malformed-named" "$mout" 'TEST-bad.xml'

  mr="$(bash "$PGRES_LIB_DIR/test-recount.sh" "$T/does-not-exist" 2>&1 || true)"
  ck_contains "missing-root-message" "$mr" 'results root'
  if bash "$PGRES_LIB_DIR/test-recount.sh" "$T/does-not-exist" >/dev/null 2>&1; then
    echo "  FAIL missing-root-refused (exit was 0)"; fails=$((fails + 1))
  else
    echo "  ok   missing-root-refused"
  fi

  mkdir -p "$T/empty/modules/auth"
  out="$(pgres::snapshot "$T/empty" "$T/empty-stage" /dev/null modules/auth)"
  ck_eq "missing-output-zero-files" "0" "$out"
  # A regular file where the stage directory must be: creating it (and so writing any
  # capture) must fail, and the caller must see that as a refused PASS.
  : > "$T/blocker"
  if pgres::snapshot "$T/empty" "$T/blocker/sub" /dev/null modules/auth >/dev/null 2>&1; then
    echo "  FAIL capture-blocked-dest-refused (exit was 0)"; fails=$((fails + 1))
  else
    echo "  ok   capture-blocked-dest-refused"
  fi

  if pgres::capture "$T/empty" "$T/blocker/run" 2 executed 0 /dev/null modules/auth >/dev/null 2>&1; then
    echo "  FAIL capture-refuses-on-write-failure (exit was 0)"; fails=$((fails + 1))
  else
    echo "  ok   capture-refuses-on-write-failure"
  fi

  pgres::capture "$T/empty" "$T/skipped-run" 9 skipped 0 "" >/dev/null 2>&1
  ck_contains "skipped-status-recorded" "$(cat "$T/skipped-run/MANIFEST.txt" 2>/dev/null)" 'stage=stage-9 status=skipped exit=0'

  if [ "$fails" -eq 0 ]; then
    echo "  results self-test: PASS"
    return 0
  fi
  echo "  results self-test: FAIL ($fails failure(s))"
  return 1
}
