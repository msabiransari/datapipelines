#!/usr/bin/env bash
# Developer-local handback checks. Full Gate A belongs to the lander.
# --plan [base] is read-only. Default stops after failure; --continue-on-failure
# explicitly collects later diagnostics. XML and logs survive each completed stage.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT" || exit 2
source "$ROOT/scripts/pregate-2b-lib.sh"
source "$ROOT/scripts/pregate-results-lib.sh"
source "$ROOT/scripts/pregate-plan-lib.sh"
BASE=origin/main; preview=0; diagnostic=0; selftest=0; base_set=0; help=0
usage() { echo 'Usage: pregate.sh [--plan | --continue-on-failure] [base-ref] | --self-test'; }
for arg in "$@"; do
  case "$arg" in
    --plan) [ "$preview" -eq 0 ] || { usage >&2; exit 2; }; preview=1 ;;
    --continue-on-failure) [ "$diagnostic" -eq 0 ] || { usage >&2; exit 2; }; diagnostic=1 ;;
    --self-test) selftest=1 ;;
    --help|-h) help=1 ;;
    -*) echo "Unknown argument: $arg" >&2; usage >&2; exit 2 ;;
    *) [ "$base_set" -eq 0 ] || { usage >&2; exit 2; }; BASE="$arg"; base_set=1 ;;
  esac
done
if [ "$help" -eq 1 ]; then
  [ "$#" -eq 1 ] || { usage >&2; exit 2; }; usage; exit 0
fi
if [ "$selftest" -eq 1 ]; then
  [ "$#" -eq 1 ] || { usage >&2; exit 2; }
  (pg2b::self_test); s_sel=$?
  (pgres::self_test); s_res=$?
  bash "$ROOT/scripts/pregate-safety-test.sh"; s_safe=$?
  [ "$s_sel" -eq 0 ] && [ "$s_res" -eq 0 ] && [ "$s_safe" -eq 0 ]; exit $?
fi
[ "$preview" -eq 0 ] || [ "$diagnostic" -eq 0 ] || { usage >&2; exit 2; }
[ "$preview" -eq 1 ] || start="$(pgres::now_ms)" || exit 1
pgplan::build "$BASE" || exit $?
if [ "$preview" -eq 1 ]; then pgplan::print; exit $?; fi
source "$ROOT/scripts/lib/verification-lock.sh"
verification::lock pregate "$ROOT" || exit $?
# Lock precedes ALL evidence mutation and work. Children close its descriptor.
LOGDIR="$ROOT/.pregate-logs"
[ ! -L "$LOGDIR" ] && [ ! -L "$LOGDIR/runs" ] || { echo 'Refusing symlinked evidence root' >&2; exit 1; }
mkdir -p "$LOGDIR" || exit 1
RUN_ID="$(pgres::new_run_id "$LOGDIR")" || exit 1
RUN_DIR="$(pgres::run_dir "$LOGDIR" "$RUN_ID")"
MANIFEST="$RUN_DIR/MANIFEST.txt"
snap=0; prune=0; failed=0; stopped_by=none
pgres::manifest_init "$MANIFEST" "$RUN_ID" "$BASE" "$PG_MB" "${PG_HEAD:0:8}" || snap=1
pgres::prune_runs "$LOGDIR" "$RUN_ID" || prune=1
pgplan::print > "$RUN_DIR/PLAN.txt" || snap=1
for m in "${PG_DEFERRED[@]}"; do
  pgres::manifest_stage "$MANIFEST" "2b-${m#tests/}" deferred 0 Gate-A-required || snap=1
done
[ "$snap" -eq 0 ] && [ "$prune" -eq 0 ] || { failed=1; stopped_by=bookkeeping; }
echo "Pre-gate base=$BASE merge-base=$PG_MB head=$PG_HEAD evidence=$RUN_DIR"
pgplan::print
# Numeric 125 preserves the exit-field grammar while meaning NOT RUN, never success.
declare -A exits=([1]=125 [2]=125 [2b]=125 [3]=125 [4]=125)
declare -A logs=([1]=1-lint.log [2]=2-touched-modules.log [2b]=2b-tests-modules.log [3]=3-guards.log [4]=4-compile-all-tests.log)

for stage in 1 2 2b 3 4; do
  if [ "$failed" -ne 0 ] && [ "$diagnostic" -eq 0 ]; then
    pgres::manifest_stage "$MANIFEST" "stage-$stage" not-run 125 "reason=after-$stopped_by elapsed_ms=0" || snap=1
    printf 'stage=%s disposition=not-run reason=after-%s\n' "$stage" "$stopped_by" >> "$RUN_DIR/COMMANDS.txt" || snap=1
    echo "  $stage NOT RUN after $stopped_by"
    continue
  fi
  before="$(pgres::now_ms)" || { snap=1; before="$start"; }
  args=(); roots=(); status=executed; rc=0
  if [ "$stage" = 3 ]; then
    # Only this invocation's successful unfiltered module checks can cover guards.
    for m in "${PG_MODULES[@]}"; do
      if [ "${exits[2]}" -eq 0 ] && [ "$snap" -eq 0 ] && [ -n "${GUARDS[$m]+x}" ]; then
        classes=(); read -r -a classes <<< "${GUARDS[$m]}"
        if pgres::guards_covered "$RUN_DIR/2" "$m" "${classes[@]}"; then
          rel="${m#:}"
          if printf 'covered module=%s classes=%s stage=2 evidence=2/%s/build/test-results/test\n' \
            "$m" "${GUARDS[$m]}" "${rel//:/\/}" >> "$RUN_DIR/GUARD-COVERAGE.txt"; then
            PG_COVERED[$m]=1
          else snap=1; fi
        fi
      fi
    done
    pgplan::guard_args
  fi
  pgplan::args "$stage" args
  case "$stage" in 2) roots=("${PG_ROOTS2[@]}") ;; 2b) roots=("${PG_ROOTS2B[@]}") ;; 3) roots=("${PG_ROOTS3[@]}") ;; esac
  pgplan::command "$stage" "${args[@]}" >> "$RUN_DIR/COMMANDS.txt" || snap=1
  # Do not launch after an accounting failure even if the preceding test was green.
  if [ "$snap" -ne 0 ] && [ "$diagnostic" -eq 0 ]; then
    status=not-run; rc=125
  elif [ "${#args[@]}" -eq 0 ]; then
    status=skipped
    [ "$stage" != 3 ] || status=covered
  else
    if [ "$stage" = 2 ]; then
      for m in "${PG_MODULES[@]}"; do
        rm -f "modules/${m##*:}/build/reports/kover/report.xml" || snap=1
      done
    fi
    if [ "$snap" -eq 0 ] || [ "$diagnostic" -eq 1 ]; then
      ./gradlew "${args[@]}" 9>&- > "$LOGDIR/${logs[$stage]}" 2>&1; rc=$?
      # Preserve the actual stage log before any later stage or invocation overwrites it.
      cp "$LOGDIR/${logs[$stage]}" "$RUN_DIR/${logs[$stage]}" || snap=1
    else
      status=not-run; rc=125
    fi
  fi
  exits[$stage]="$rc"
  if [ "$status" = executed ] && [[ "$stage" == 2 || "$stage" == 2b || "$stage" == 3 ]]; then
    pgres::capture "$ROOT" "$RUN_DIR" "$stage" executed "$rc" "$LOGDIR/${logs[$stage]}" "${roots[@]}" || snap=1
  else
    reason=modules-none
    [ "$status" != executed ] || reason=completed
    [ "$status" != covered ] || reason=covered-by-stage-2
    [ "$status" != not-run ] || reason=bookkeeping-failure
    pgres::manifest_stage "$MANIFEST" "stage-$stage" "$status" "$rc" "reason=$reason" || snap=1
  fi
  if [ "$stage" = 2 ] && [ "$status" = executed ]; then
    # Coverage floors are still enforced by check; report each number and preserve XML.
    for m in "${PG_MODULES[@]}"; do
      xml="modules/${m##*:}/build/reports/kover/report.xml"
      floor="$(sed -nE "s/.*\"$m\" to ([0-9]+).*/\1/p" buildSrc/src/main/kotlin/CommonConventionsPlugin.kt)"
      if [ -f "$xml" ]; then
        mkdir -p "$RUN_DIR/2/coverage" || snap=1
        cp "$xml" "$RUN_DIR/2/coverage/${m##*:}.xml" || snap=1
        pgres::coverage_report "$xml" "$m" "${floor:-none}" || snap=1
      else echo "  $m: no coverage report, floor ${floor:-none}"; fi
    done
  fi
  after="$(pgres::now_ms)" || { snap=1; after="$before"; }
  pgres::elapsed "$MANIFEST" "$stage" "$before" "$after" || snap=1
  printf 'stage=%s disposition=%s exit=%s\n' "$stage" "$status" "$rc" >> "$RUN_DIR/COMMANDS.txt" || snap=1
  echo "  $stage $status EXIT=$rc elapsed_ms=$((after - before))"
  if [ "$rc" -ne 0 ] || [ "$snap" -ne 0 ]; then
    failed=1; stopped_by="$stage"
    if [ "$status" = executed ]; then tail -n 24 "$LOGDIR/${logs[$stage]}"; fi
  fi
done
finish="$(pgres::now_ms)" || { snap=1; finish="$start"; }
pgres::elapsed "$MANIFEST" total "$start" "$finish" || snap=1
outcome=FAIL
[ "$failed" -eq 0 ] && [ "$snap" -eq 0 ] && [ "$prune" -eq 0 ] && outcome=PASS
# Final manifest write is mandatory too. It must precede any PASS publication.
if ! pgres::manifest_verdict "$MANIFEST" "$outcome" "$RUN_ID"; then snap=1; outcome=FAIL; fi
line="PRE-GATE $outcome base=$BASE merge-base=${PG_MB:0:8} head=${PG_HEAD:0:8} at=$(date -u '+%Y-%m-%dT%H:%M:%SZ') lint=${exits[1]} mod=${exits[2]} t2b=${exits[2b]} grd=${exits[3]} cmp=${exits[4]} snap=$snap prune=$prune run=$RUN_ID deferred=${PG_DEFERRED[*]:-none} elapsed_ms=$((finish - start)) diagnostic=$diagnostic"
if ! printf '%s\n' "$line" >> "$LOGDIR/0-verdict.log"; then
  pgres::manifest_verdict "$MANIFEST" FAIL "$RUN_ID" || true
  echo 'PRE-GATE FAIL: cannot append verdict' >&2; exit 1
fi
printf '%s\n' "$line"
[ "$outcome" = PASS ]
