#!/usr/bin/env bash
#
# run.sh — the security mutation runner (security-assurance record §9, ratified B6; lane 217a, A.4).
#
#   scripts/security-mutations/run.sh              # all ten mutations
#   scripts/security-mutations/run.sh 03 07        # the named ones
#   scripts/security-mutations/run.sh --self-check # prove the runner fails an undetected mutation
#
# Each NN-<name>.sh beside this file declares ONE deliberate security defect as a literal sed
# pair (FROM → TO in one production file, each asserted to match exactly once) and the ONE test
# class — and the one test in it — that must go red. For every mutation the runner:
#   1. applies the pair and proves the file changed in exactly that one place;
#   2. runs that class alone (--rerun: the task executes, never UP-TO-DATE), and demands the named
#      test FAILED — a compile error, a crash or a missing result file is "no verdict", never a
#      detection (record §9: "a compilation error ... or a tooling crash is not a successful
#      falsification");
#   3. reverts with the inverse pair — never `git checkout --`, which would also revert uncommitted
#      work (MISTAKES.md) — and proves the tree is byte-identical to where it started;
#   4. runs the class again and demands it GREEN.
# A mutation whose class stays green FAILS the run: "mutation NN not detected".
#
# Where it may run: a disposable checkout only. It REFUSES a dirty tree, the primary checkout (any
# path without /.claude/worktrees/), and `main` checked out. It edits production sources by design;
# a trap reverts the mutation in flight on any exit. Never per PR — weekly (the scheduled workflow)
# and by hand when modules/auth, ScopeInterceptor, the MCP dispatcher or PublicPaths change
# (DEVELOPMENT.md §10.2).
#
# Output: $MUTATION_OUT (default build/security-mutations/): one log per run and summary.md — base
# SHA, patch hash, the failing assertion, load average and duration per mutation.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(git -C "$HERE" rev-parse --show-toplevel)" || exit 2
cd "$ROOT" || exit 2
OUT="${MUTATION_OUT:-$ROOT/build/security-mutations}"
PRIMARY_MARKER="/.claude/worktrees/"

refuse() { echo "run.sh: REFUSED — $*" >&2; exit 3; }

# ---- where it may run ----------------------------------------------------------------------------
case "$ROOT/" in *"$PRIMARY_MARKER"*) ;; *) refuse "not a disposable checkout ($ROOT has no $PRIMARY_MARKER): mutations edit production sources";; esac
[ "$(git symbolic-ref -q HEAD || echo detached)" = "refs/heads/main" ] && refuse "main is checked out here"
[ -z "$(git status --porcelain)" ] || refuse "the working tree is dirty — commit or stash first; a revert must restore exactly what was there"

SELF_CHECK=0
if [ "${1:-}" = "--self-check" ]; then SELF_CHECK=1; shift; fi
mkdir -p "$OUT"
: > "$OUT/verdict.txt"
BASE="$(git rev-parse HEAD)"
SUMMARY="$OUT/summary.md"

# ---- the literal sed pair --------------------------------------------------------------------------
esc_pattern() { printf '%s' "$1" | sed -e 's/[]\/$*.^[]/\\&/g'; }
esc_replacement() { printf '%s' "$1" | sed -e 's/[\/&]/\\&/g'; }
count() { grep -cF -- "$2" "$1" || true; }

swap() { # swap <file> <from> <to> — exactly one line holds <from>, none holds <to>, afterwards the reverse
  local file="$1" from="$2" to="$3"
  case "$to" in *"$from"*) echo "ambiguous pair: TO contains FROM — the counts cannot tell them apart" >&2; return 1;; esac
  case "$from" in *"$to"*) echo "ambiguous pair: FROM contains TO — the counts cannot tell them apart" >&2; return 1;; esac
  [ "$(count "$file" "$from")" = 1 ] || { echo "anchor not found exactly once in $file: $from" >&2; return 1; }
  [ "$(count "$file" "$to")" = 0 ] || { echo "replacement already present in $file: $to" >&2; return 1; }
  sed -i "s/$(esc_pattern "$from")/$(esc_replacement "$to")/" "$file"
  [ "$(count "$file" "$from")" = 0 ] && [ "$(count "$file" "$to")" = 1 ] || { echo "the swap did not land in $file" >&2; return 1; }
}

APPLIED=""
revert_in_flight() {
  if [ -n "$APPLIED" ]; then
    # shellcheck disable=SC1090
    ( source "$APPLIED"; swap "$ROOT/$TARGET" "$TO" "$FROM" ) && echo "run.sh: reverted the mutation in flight ($APPLIED)" >&2
    APPLIED=""
  fi
}
trap revert_in_flight EXIT INT TERM

# ---- one gradle run of one class, judged from its XML ----------------------------------------------
results_dir() { local task="$1"; task="${task#:}"; task="${task%:test}"; echo "$ROOT/${task//://}/build/test-results/test"; }

run_class() { # run_class <log> → gradle exit code
  local log="$1"
  rm -f "$(results_dir "$EXPECT_TASK")/TEST-$EXPECT_CLASS.xml"
  ./gradlew "$EXPECT_TASK" --rerun --tests "$EXPECT_CLASS" -x "${EXPECT_TASK%:test}:verifyTestsExecuted" > "$log" 2>&1
  echo $?
}

judge() { # judge red|green → prints "RED <assertion>" / "GREEN n tests" / "NOT-RED" / "NOT-GREEN <why>" / "NO-VERDICT"
  python3 - "$1" "$(results_dir "$EXPECT_TASK")/TEST-$EXPECT_CLASS.xml" "$EXPECT_TEST" <<'PY'
import sys, os, xml.etree.ElementTree as ET
want, path, test = sys.argv[1:4]
if not os.path.exists(path):
    print("NO-VERDICT (no result file: a compile error or a crash)"); sys.exit(0)
root = ET.parse(path).getroot()
named = [tc for tc in root.findall("testcase") if test in tc.get("name", "")]
if not named:
    print(f"NO-VERDICT (no test named '{test}' ran)"); sys.exit(0)
failed = [tc for tc in named if tc.find("failure") is not None or tc.find("error") is not None]
if want == "red":
    if failed:
        el = failed[0].find("failure") if failed[0].find("failure") is not None else failed[0].find("error")
        first = (el.get("message") or el.get("type") or "").strip().splitlines()[0][:160].replace("|", "\\|")
        print(f"RED {first}")
    else:
        print("NOT-RED")
else:
    bad = int(root.get("failures", "0")) + int(root.get("errors", "0"))
    print(f"GREEN {root.get('tests')} tests" if not failed and bad == 0 else f"NOT-GREEN failures={root.get('failures')} errors={root.get('errors')}")
PY
}

# ---- the run -------------------------------------------------------------------------------------------
if [ "$SELF_CHECK" = 1 ]; then
  MUTATIONS=("$HERE/selfcheck/00-undetectable.sh")
elif [ "$#" -gt 0 ]; then
  MUTATIONS=(); for id in "$@"; do MUTATIONS+=("$(ls "$HERE"/"$id"-*.sh)"); done
else
  mapfile -t MUTATIONS < <(ls "$HERE"/[0-9][0-9]-*.sh)
fi

{
  echo "# Security mutations — $(date -u '+%Y-%m-%d %H:%M UTC')"
  echo
  echo "Base \`$BASE\`. Each row: the defect applied, its class run RED on the named test, reverted, run GREEN."
  echo
  echo "| # | Mutation | Record §9 | Expected red | Red (the failing assertion) | Green | Load red/green | Seconds | Patch |"
  echo "|---|---|---|---|---|---|---|---|---|"
} > "$SUMMARY"

failures=0
for m in "${MUTATIONS[@]}"; do
  unset MUTATION_NAME RECORD_ROW TARGET FROM TO EXPECT_TASK EXPECT_CLASS EXPECT_TEST
  # shellcheck disable=SC1090
  source "$m"
  id="$(basename "$m" | cut -d- -f1)"
  start=$(date +%s)
  swap "$ROOT/$TARGET" "$FROM" "$TO" || { echo "mutation $id: could not apply" | tee -a "$SUMMARY"; failures=$((failures + 1)); continue; }
  APPLIED="$m"
  patch_hash="$(git diff | sha256sum | cut -c1-12)"
  [ "$(git diff --name-only | wc -l)" = 1 ] || { echo "mutation $id: touched more than one file" >&2; failures=$((failures + 1)); }
  load_red="$(cut -d' ' -f1 /proc/loadavg)"
  run_class "$OUT/$id-red.log" > /dev/null
  red="$(judge red)"
  swap "$ROOT/$TARGET" "$TO" "$FROM" || { echo "mutation $id: the revert did not land — STOP" >&2; exit 1; }
  APPLIED=""
  [ -z "$(git status --porcelain)" ] || { echo "mutation $id: the tree is not byte-identical after the revert — STOP" >&2; exit 1; }
  load_green="$(cut -d' ' -f1 /proc/loadavg)"
  run_class "$OUT/$id-green.log" > /dev/null
  green="$(judge green)"
  seconds=$(( $(date +%s) - start ))
  echo "| $id | $MUTATION_NAME | $RECORD_ROW | \`${EXPECT_CLASS##*.}\` — $EXPECT_TEST | $red | $green | $load_red / $load_green | $seconds | \`$patch_hash\` |" >> "$SUMMARY"
  case "$red" in
    RED*) ;;
    NOT-RED) echo "mutation $id not detected: ${EXPECT_CLASS##*.} stayed green with the defect applied" | tee -a "$OUT/verdict.txt"; failures=$((failures + 1)) ;;
    *) echo "mutation $id: $red — no verdict (see $OUT/$id-red.log)" | tee -a "$OUT/verdict.txt"; failures=$((failures + 1)) ;;
  esac
  case "$green" in GREEN*) ;; *) echo "mutation $id: after the revert, $green (see $OUT/$id-green.log)" | tee -a "$OUT/verdict.txt"; failures=$((failures + 1)) ;; esac
done

echo >> "$SUMMARY"
if [ "$SELF_CHECK" = 1 ]; then
  # The self-check PASSES only when the runner judged the undetectable mutation "not detected".
  if grep -q "mutation 00 not detected" "$OUT/verdict.txt" 2>/dev/null; then
    echo "SELF-CHECK PASS — the runner fails a mutation its class does not detect" | tee -a "$SUMMARY"; exit 0
  fi
  echo "SELF-CHECK FAIL — the runner did not report the undetectable mutation" | tee -a "$SUMMARY"; exit 1
fi
if [ "$failures" -eq 0 ]; then
  echo "MUTATIONS PASS — ${#MUTATIONS[@]} detected, ${#MUTATIONS[@]} clean after revert" | tee -a "$SUMMARY"; exit 0
fi
echo "MUTATIONS FAIL — $failures problem(s), named above and in $OUT/verdict.txt" | tee -a "$SUMMARY"; exit 1
