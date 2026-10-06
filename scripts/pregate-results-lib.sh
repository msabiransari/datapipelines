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
# RETENTION (#447): every run creates a directory; nothing used to prune them. Once per
# invocation, AFTER this run's own directory exists, `pgres::prune_runs` keeps the newest
# `PREGATE_KEEP_RUNS` (default 10, positive decimal integer) and deletes only the older,
# OVER-BOUND candidates. A candidate must be an immediate, non-symlink child of
# `.pregate-logs/runs` whose name matches the run-id grammar this library emits, and its
# canonical path must resolve back under that runs root. The current run and any run whose
# embedded PID is still alive are never deleted, so retention may temporarily exceed N while
# concurrent invocations overlap — the honest cost of not deleting active evidence. Ordering
# is deterministic `LC_ALL=C` byte order of the run id: its UTC-stamp prefix makes that
# chronological, and run ids are unique (`pgres::new_run_id` suffixes collisions) so there are
# no equal keys. Invalid input fails BEFORE any deletion. A prune failure is carried into the
# verdict (`prune=1`) exactly like a snapshot failure: it refuses a PASS.
#
# PROVENANCE (#447): the copied XML alone does not prove the stage ran that task — a task
# directory left from an earlier run is copied with no mark. `pgres::write_provenance` keeps
# the stage log's status lines AND emits `# NO-STATUS-LINE <repo-relative-task-directory>` for
# every copied task directory with no exact `> Task :…` line for it. A missing log annotates
# every copied directory; `UP-TO-DATE`/`FROM-CACHE` lines are preserved but are themselves no
# proof of fresh execution.
#
# SECURITY: snapshots stay under this worktree's `.pregate-logs`, mirror only the
# repo-relative paths of the `modules/<m>` and `tests/<m>` roots the stage ran, and walk
# no symlinked or external path. Pruning deletes only containment-checked immediate children;
# it rejects a symlinked runs root, never follows a candidate symlink, and never uses a broad
# glob. Test fixtures that stand in for Gradle record their argv and REFUSE anything they did
# not fake — never a fallthrough to the real binary.
#
# Entry points (all pure over the filesystem except the self-test's git fixture):
#   pgres::new_run_id <logdir>                    — unique, filesystem-safe run id
#   pgres::run_dir <logdir> <run-id>              — the run directory path
#   pgres::is_run_id <id>                         — exit 0 iff id matches the emitted grammar
#   pgres::run_pid <id>                           — the PID embedded in a run id
#   pgres::prune_runs <logdir> <current-run-id>   — bounded retention of run dirs (#447)
#   pgres::allowed_root <rel>                     — exit 0 iff rel is modules/<m> or tests/<m>
#   pgres::snapshot <root> <stage-dir> <log> <roots...>  — copy XML, print file count
#   pgres::write_provenance <log> <dest>          — record status lines + NO-STATUS-LINE marks
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

# The EXACT grammar `pgres::new_run_id` emits: UTC stamp `YYYYMMDDThhmmssZ`, a short git
# SHA (lowercase hex) or the literal `nogit`, the PID, and an optional numeric collision
# suffix. Retention treats only names matching this as candidates, so unrelated directories
# are never touched.
pgres::is_run_id() {
  [[ "$1" =~ ^[0-9]{8}T[0-9]{6}Z-(nogit|[0-9a-f]+)-[0-9]+(-[0-9]+)?$ ]]
}

# The PID embedded in a run id (assumes pgres::is_run_id passed). `$$` in the id is the
# pre-gate process's own pid, so a run still in flight is identifiable by liveness.
pgres::run_pid() {
  printf '%s' "$1" | sed -E 's/^[0-9]{8}T[0-9]{6}Z-[^-]+-([0-9]+)(-[0-9]+)?$/\1/'
}

# pgres::prune_runs <logdir> <current-run-id>
# Once per invocation, keep the newest PREGATE_KEEP_RUNS (default 10) run directories and
# delete the older, over-bound ones. The current run and any run whose embedded PID is
# alive are protected, so the visible count may temporarily exceed N. Returns non-zero on
# invalid PREGATE_KEEP_RUNS (before any deletion), a symlinked/non-directory runs root, or a
# refused/unsuccessful deletion — the caller must refuse a PASS on that.
pgres::prune_runs() {
  local logdir="$1" current="$2"
  local keep="${PREGATE_KEEP_RUNS:-10}"
  # A positive decimal integer, no sign, no leading zero, bounded to 9 digits so the shell
  # arithmetic below cannot overflow into a negative bound (which would delete the NEWEST).
  if [[ ! "$keep" =~ ^[1-9][0-9]{0,8}$ ]]; then
    echo "pgres::prune_runs: refusing PREGATE_KEEP_RUNS='${PREGATE_KEEP_RUNS:-}' (positive decimal integer required)" >&2
    return 1
  fi

  local runs="$logdir/runs"
  [ -e "$runs" ] || return 0
  if [ -L "$runs" ]; then
    echo "pgres::prune_runs: refusing symlinked runs root '$runs'" >&2; return 1
  fi
  if [ ! -d "$runs" ]; then
    echo "pgres::prune_runs: refusing runs root '$runs' that is not a directory" >&2; return 1
  fi
  local runs_real
  runs_real="$(realpath -m -- "$runs")" || {
    echo "pgres::prune_runs: cannot resolve runs root '$runs'" >&2; return 1; }

  # Candidates: immediate non-symlink child directories whose name is our run-id grammar and
  # whose canonical path is still an immediate child of the canonical runs root.
  local d base real
  local -a candidates=()
  for d in "$runs"/*; do
    [ -e "$d" ] || continue
    [ -L "$d" ] && continue
    [ -d "$d" ] || continue
    base="$(basename "$d")"
    pgres::is_run_id "$base" || continue
    real="$(realpath -m -- "$d")" || continue
    [ "$(dirname "$real")" = "$runs_real" ] || continue
    candidates+=("$base")
  done

  local total="${#candidates[@]}"
  [ "$total" -le "$keep" ] && return 0

  # Deterministic order by run id, byte-wise (LC_ALL=C): the UTC stamp prefix is chronological
  # and run ids are unique, so there are no ties. The newest are the tail.
  local -a sorted=()
  while IFS= read -r d; do sorted+=("$d"); done \
    < <(printf '%s\n' "${candidates[@]}" | LC_ALL=C sort)

  local i id pid protected=0
  local -a to_delete=()
  for ((i = 0; i < total - keep; i++)); do
    id="${sorted[$i]}"
    if [ "$id" = "$current" ]; then
      protected=$((protected + 1)); continue
    fi
    pid="$(pgres::run_pid "$id")"
    if [ -d "/proc/$pid" ] || kill -0 "$pid" 2>/dev/null; then
      protected=$((protected + 1)); continue
    fi
    to_delete+=("$id")
  done

  local failed=0
  for id in ${to_delete[@]+"${to_delete[@]}"}; do
    d="$runs/$id"
    real="$(realpath -m -- "$d")" || { failed=1; continue; }
    if [ "$(dirname "$real")" != "$runs_real" ]; then
      echo "pgres::prune_runs: refusing out-of-root candidate '$d' (resolved '$real')" >&2
      failed=1; continue
    fi
    rm -rf -- "$d" || { echo "pgres::prune_runs: cannot delete '$d'" >&2; failed=1; continue; }
  done
  if [ "$protected" -gt 0 ]; then
    echo "pgres::prune_runs: kept $protected protected run(s) (current or live PID) beyond keep=$keep" >&2
  fi
  [ "$failed" -eq 0 ]
}

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

# The Gradle task path a copied task directory corresponds to: `modules/auth/build/test-results/test`
# → `:modules:auth:test`. Prints nothing and returns non-zero if the shape is not
# `<modules|tests>/<m>/build/test-results/<task>`.
pgres::_task_path() {
  local rel="$1" prefix task
  case "$rel" in
    modules/*/build/test-results/*|tests/*/build/test-results/*) ;;
    *) return 1 ;;
  esac
  prefix="${rel%/build/test-results/*}"
  task="${rel##*/build/test-results/}"
  [ -n "$prefix" ] && [ -n "$task" ] || return 1
  printf ':%s:%s' "${prefix//\//:}" "$task"
}

# Emits `# NO-STATUS-LINE <repo-relative-task-directory>` for every copied task directory
# (a directory holding copied *.xml) that has no EXACT corresponding `> Task :…` line in the
# stage log. Deterministic: C-sorted, one line per directory (multiple XML in one task dir
# still yield one). An absent log therefore annotates every copied directory. Exact token
# comparison keeps similarly-prefixed task names distinct (`test` vs `testExtra`).
pgres::_no_status_annotations() {
  local stage_dir="$1" log="$2" f tdir line tok d taskpath
  local -a files=() tdirs=() uniq=()
  while IFS= read -r f; do files+=("$f"); done \
    < <(find "$stage_dir" -type f -name '*.xml' 2>/dev/null)
  [ "${#files[@]}" -eq 0 ] && return 0
  for f in "${files[@]}"; do tdirs+=("$(dirname "${f#"$stage_dir/"}")"); done
  while IFS= read -r tdir; do uniq+=("$tdir"); done \
    < <(printf '%s\n' "${tdirs[@]}" | LC_ALL=C sort -u)

  local -A logged=()
  if [ -f "$log" ]; then
    while IFS= read -r line; do
      case "$line" in
        '> Task '*) ;;
        *) continue ;;
      esac
      tok="${line#"> Task "}"
      tok="${tok%%[[:space:]]*}"
      [ -n "$tok" ] && logged["$tok"]=1
    done < <(grep '^> Task ' "$log" 2>/dev/null)
  fi

  for d in "${uniq[@]}"; do
    taskpath="$(pgres::_task_path "$d")" || continue
    if [ -n "${logged[$taskpath]+x}" ]; then
      continue
    fi
    printf '# NO-STATUS-LINE %s\n' "$d"
  done
  return 0
}

# Records how Gradle reported each test task in the stage's log AND, for every copied task
# directory, whether the log holds an exact `> Task` line for it — the honest provenance of
# the copied XML. A `UP-TO-DATE`/`FROM-CACHE` status line is preserved, but neither it nor a
# copied XML file proves fresh execution; a directory with NO line is annotated, and an
# absent log annotates every copied directory.
pgres::write_provenance() {
  local log="$1" dest="$2" stage_dir
  stage_dir="$(dirname "$dest")"
  {
    printf '# provenance for %s — test-task status lines from the stage log\n' "$(basename "$stage_dir")"
    if [ -f "$log" ]; then
      grep -E '^> Task :(modules|tests):[a-z-]+:(test|breachSuite|editorJsTest|securityAssuranceTest)( [A-Z-]+)?' "$log" \
        | sort -u || true
    else
      printf '# (stage log %s is absent)\n' "$log"
    fi
    pgres::_no_status_annotations "$stage_dir" "$log"
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
  mkdir -p "$F/scripts/lib"
  cp "$PGRES_LIB_DIR/lib/verification-lock.sh" "$F/scripts/lib/"
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
  ( cd "$F" && XDG_CACHE_HOME="$F/.fixture-cache" bash scripts/pregate.sh HEAD > pregate.out 2>&1 )
}

pgres::self_test() {
  local T
  T="$(mktemp -d /tmp/pgres-selftest.XXXXXX)"
  trap "rm -rf '$T'" EXIT
  local fails=0
  # Hermetic: the retention cases must see the default unless they set the override.
  unset PREGATE_KEEP_RUNS

  seed_runs() { # seed_runs <runs-dir> <count> → <count> valid run dirs, ascending stamps
    local dir="$1" n="$2" i id
    for i in $(seq 1 "$n"); do
      id="$(printf '20260101T%06dZ-abcdef1-%d' "$i" "$((9000 + i))")"
      mkdir -p "$dir/$id"; : > "$dir/$id/MANIFEST.txt"
    done
  }
  count_runs() { # count_runs <runs-dir> → number of run-id-shaped child directories
    find "$1" -mindepth 1 -maxdepth 1 -type d -name '2026*' 2>/dev/null | wc -l
  }

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

  # ---- 5. provenance: NO-STATUS-LINE annotations (#447) -----------------------
  local PR="$T/prov-root" PS="$T/prov-stage" PLC="$T/prov.log"
  mkdir -p "$PR/modules/auth/build/test-results/test" \
           "$PR/modules/auth/build/test-results/breachSuite" \
           "$PR/modules/auth/build/test-results/testExtra" \
           "$PR/tests/integration-tests/build/test-results/test"
  printf '<testsuite name="a" tests="1" failures="0" errors="0" skipped="0"/>' > "$PR/modules/auth/build/test-results/test/TEST-a.xml"
  printf '<testsuite name="b" tests="1" failures="0" errors="0" skipped="0"/>' > "$PR/modules/auth/build/test-results/test/TEST-b.xml"
  printf '<testsuite name="c" tests="1" failures="0" errors="0" skipped="0"/>' > "$PR/modules/auth/build/test-results/breachSuite/TEST-c.xml"
  printf '<testsuite name="c2" tests="1" failures="0" errors="0" skipped="0"/>' > "$PR/modules/auth/build/test-results/breachSuite/TEST-c2.xml"
  printf '<testsuite name="d" tests="1" failures="0" errors="0" skipped="0"/>' > "$PR/modules/auth/build/test-results/testExtra/TEST-d.xml"
  printf '<testsuite name="e" tests="1" failures="0" errors="0" skipped="0"/>' > "$PR/tests/integration-tests/build/test-results/test/TEST-e.xml"
  {
    echo '> Task :modules:auth:test'
    echo '> Task :modules:auth:test UP-TO-DATE'
    echo '> Task :tests:integration-tests:test FROM-CACHE'
    echo 'BUILD SUCCESSFUL'
  } > "$PLC"
  pgres::snapshot "$PR" "$PS" "$PLC" modules/auth tests/integration-tests >/dev/null
  ck_ok "prov-status-lines-retained" grep -qF '> Task :modules:auth:test UP-TO-DATE' "$PS/PROVENANCE.txt"
  ck_ok "prov-from-cache-retained" grep -qF '> Task :tests:integration-tests:test FROM-CACHE' "$PS/PROVENANCE.txt"
  ck_ok "prov-logged-test-not-annotated" bash -c "! grep -qxF '# NO-STATUS-LINE modules/auth/build/test-results/test' '$PS/PROVENANCE.txt'"
  ck_ok "prov-logged-tests-not-annotated" bash -c "! grep -qxF '# NO-STATUS-LINE tests/integration-tests/build/test-results/test' '$PS/PROVENANCE.txt'"
  ck_ok "prov-unlogged-breach-annotated" grep -qxF '# NO-STATUS-LINE modules/auth/build/test-results/breachSuite' "$PS/PROVENANCE.txt"
  ck_ok "prov-prefix-similar-distinct" grep -qxF '# NO-STATUS-LINE modules/auth/build/test-results/testExtra' "$PS/PROVENANCE.txt"
  ck_eq "prov-multi-xml-one-annotation" "1" "$(grep -cxF '# NO-STATUS-LINE modules/auth/build/test-results/breachSuite' "$PS/PROVENANCE.txt")"
  ck_eq "prov-annotation-count" "2" "$(grep -c 'NO-STATUS-LINE' "$PS/PROVENANCE.txt")"
  ck_eq "prov-logged-does-not-annotate-prefixed" "0" "$(grep -cxF '# NO-STATUS-LINE modules/auth/build/test-results/test' "$PS/PROVENANCE.txt")"

  pgres::snapshot "$PR" "$T/prov-missing" "$T/no-such.log" modules/auth tests/integration-tests >/dev/null
  ck_contains "prov-missing-log-noted" "$(cat "$T/prov-missing/PROVENANCE.txt")" 'is absent'
  ck_eq "prov-missing-log-annotates-all" "4" "$(grep -c 'NO-STATUS-LINE' "$T/prov-missing/PROVENANCE.txt")"

  # ---- 6. retention: bound, order, protection, symlinks, invalid input --------
  local K1 kr1
  K1="$T/keep-default"; kr1="$K1/runs"; mkdir -p "$kr1"
  seed_runs "$kr1" 15
  mkdir -p "$kr1/not-a-run-dir"; : > "$kr1/loose-file"
  if PREGATE_KEEP_RUNS= pgres::prune_runs "$K1" "" >/dev/null 2>&1; then
    echo "  ok   prune-default-runs"
  else echo "  FAIL prune-default-runs (non-zero)"; fails=$((fails + 1)); fi
  ck_eq "prune-default-keeps-10" "10" "$(count_runs "$kr1")"
  ck_ok "prune-default-keeps-newest" test -d "$kr1/20260101T000015Z-abcdef1-9015"
  ck_ok "prune-default-drops-oldest" test ! -d "$kr1/20260101T000001Z-abcdef1-9001"
  ck_ok "prune-keeps-unrelated-dir" test -d "$kr1/not-a-run-dir"
  ck_ok "prune-keeps-unrelated-file" test -f "$kr1/loose-file"

  local K2 kr2
  K2="$T/keep-override"; kr2="$K2/runs"; mkdir -p "$kr2"; seed_runs "$kr2" 6
  PREGATE_KEEP_RUNS=2 pgres::prune_runs "$K2" "" >/dev/null 2>&1; unset PREGATE_KEEP_RUNS
  ck_eq "prune-override-keeps-2" "2" "$(count_runs "$kr2")"
  ck_ok "prune-override-keeps-newest" test -d "$kr2/20260101T000006Z-abcdef1-9006"
  ck_ok "prune-override-drops-third" test ! -d "$kr2/20260101T000004Z-abcdef1-9004"

  local K3 kr3
  K3="$T/keep-current"; kr3="$K3/runs"; mkdir -p "$kr3"; seed_runs "$kr3" 5
  PREGATE_KEEP_RUNS=2 pgres::prune_runs "$K3" "20260101T000001Z-abcdef1-9001" >/dev/null 2>&1; unset PREGATE_KEEP_RUNS
  ck_ok "prune-protects-current-old" test -d "$kr3/20260101T000001Z-abcdef1-9001"
  ck_ok "prune-current-excess-kept" test -d "$kr3/20260101T000005Z-abcdef1-9005"
  ck_eq "prune-current-excess-count" "3" "$(count_runs "$kr3")"

  local K4 kr4
  K4="$T/keep-active"; kr4="$K4/runs"; mkdir -p "$kr4"
  mkdir -p "$kr4/20260101T000000Z-abcdef1-$$" \
           "$kr4/20260101T000001Z-abcdef1-7101" \
           "$kr4/20260101T000002Z-abcdef1-7102"
  PREGATE_KEEP_RUNS=2 pgres::prune_runs "$K4" "" >/dev/null 2>&1; unset PREGATE_KEEP_RUNS
  ck_ok "prune-protects-live-pid" test -d "$kr4/20260101T000000Z-abcdef1-$$"
  ck_eq "prune-active-excess-count" "3" "$(count_runs "$kr4")"

  local K5 kr5
  K5="$T/keep-ties"; kr5="$K5/runs"; mkdir -p "$kr5"
  mkdir -p "$kr5/20260101T000000Z-abcdef1-5000" \
           "$kr5/20260101T000000Z-abcdef1-5000-2" \
           "$kr5/20260101T000000Z-abcdef1-5000-3"
  PREGATE_KEEP_RUNS=1 pgres::prune_runs "$K5" "" >/dev/null 2>&1; unset PREGATE_KEEP_RUNS
  ck_eq "prune-collision-keeps-1" "1" "$(count_runs "$kr5")"
  ck_ok "prune-collision-keeps-suffixed" test -d "$kr5/20260101T000000Z-abcdef1-5000-3"
  ck_ok "prune-collision-drops-base" test ! -d "$kr5/20260101T000000Z-abcdef1-5000"

  local K6 kr6 bad
  K6="$T/keep-invalid"; kr6="$K6/runs"; mkdir -p "$kr6"; seed_runs "$kr6" 4
  for bad in 0 00 007 -1 1.5 10x ' 3' 1000000000 abc; do
    if PREGATE_KEEP_RUNS="$bad" pgres::prune_runs "$K6" "" >/dev/null 2>&1; then
      echo "  FAIL prune-invalid-'$bad' (exit was 0)"; fails=$((fails + 1))
    else echo "  ok   prune-invalid-'$bad'"; fi
  done
  unset PREGATE_KEEP_RUNS
  ck_eq "prune-invalid-no-deletion" "4" "$(count_runs "$kr6")"

  local K7 kr7
  K7="$T/keep-symlink"; kr7="$K7/runs"; mkdir -p "$kr7"
  mkdir -p "$T/ext-sentinel"; : > "$T/ext-sentinel/keepme.txt"
  ln -s "$T/ext-sentinel" "$kr7/20260101T000000Z-abcdef1-6000"
  mkdir -p "$kr7/20260101T000001Z-abcdef1-6001" "$kr7/20260101T000002Z-abcdef1-6002"
  PREGATE_KEEP_RUNS=1 pgres::prune_runs "$K7" "" >/dev/null 2>&1; unset PREGATE_KEEP_RUNS
  ck_ok "prune-candidate-symlink-kept" test -L "$kr7/20260101T000000Z-abcdef1-6000"
  ck_ok "prune-symlink-target-untouched" test -f "$T/ext-sentinel/keepme.txt"
  ck_ok "prune-deletes-old-real" test ! -d "$kr7/20260101T000001Z-abcdef1-6001"
  ck_ok "prune-keeps-newest-real" test -d "$kr7/20260101T000002Z-abcdef1-6002"

  local K8
  K8="$T/keep-rootlink"; mkdir -p "$K8" "$T/ext-runs/20260101T000000Z-abcdef1-8000"
  : > "$T/ext-runs/keepme.txt"
  ln -s "$T/ext-runs" "$K8/runs"
  if PREGATE_KEEP_RUNS=1 pgres::prune_runs "$K8" "" >/dev/null 2>&1; then
    echo "  FAIL prune-symlink-root-refused (exit was 0)"; fails=$((fails + 1))
  else echo "  ok   prune-symlink-root-refused"; fi
  unset PREGATE_KEEP_RUNS
  ck_ok "prune-symlink-root-no-delete" test -d "$T/ext-runs/20260101T000000Z-abcdef1-8000"
  ck_ok "prune-symlink-root-sentinel" test -f "$T/ext-runs/keepme.txt"

  # ---- 7. production wiring: the REAL pregate prunes and propagates failure -----
  local W
  W="$T/wiring"
  ck_ok "wiring-fixture-built" pgres::_fixture "$W" happy
  mkdir -p "$W/.pregate-logs/runs"
  seed_runs "$W/.pregate-logs/runs" 13
  ck_ok "wiring-pregate-green" pgres::_run_fixture "$W"
  ck_eq "wiring-bounded-to-default" "10" "$(count_runs "$W/.pregate-logs/runs")"
  ck_ok "wiring-dropped-oldest" test ! -d "$W/.pregate-logs/runs/20260101T000001Z-abcdef1-9001"
  ck_ok "wiring-prune-flag-zero" grep -q 'prune=0' "$W/.pregate-logs/0-verdict.log"

  local W2
  W2="$T/wiring-bad"
  ck_ok "wiring-bad-fixture-built" pgres::_fixture "$W2" happy
  if ( cd "$W2" && PREGATE_KEEP_RUNS=0 bash scripts/pregate.sh HEAD > wiring-bad.out 2>&1 ); then
    echo "  FAIL wiring-bad-refuses-pass (exit was 0)"; fails=$((fails + 1))
  else echo "  ok   wiring-bad-refuses-pass"; fi
  ck_ok "wiring-bad-verdict-fail" grep -q 'PRE-GATE FAIL' "$W2/.pregate-logs/0-verdict.log"
  ck_ok "wiring-bad-prune-flag" grep -q 'prune=1' "$W2/.pregate-logs/0-verdict.log"

  if [ "$fails" -eq 0 ]; then
    echo "  results self-test: PASS"
    return 0
  fi
  echo "  results self-test: FAIL ($fails failure(s))"
  return 1
}
