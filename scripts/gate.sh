#!/usr/bin/env bash
#
# gate.sh — the canonical Gate A command.
#
#   ./scripts/gate.sh            # 1 cycle
#   ./scripts/gate.sh 5          # 5 consecutive cycles
#   ./scripts/gate.sh 5 --strict # also FAIL (not warn) if another build actor is active
#
# One cycle is three BARE gradle invocations:
#     ./gradlew clean      → must exit 0
#     ./gradlew build      → must exit 0   (clean-state build)
#     ./gradlew build      → must exit 0   (incremental build)
#
# The incremental pass is deliberate: it has its own failure history, and a gate
# that only ever runs from clean never exercises the path developers use most.
#
# ---------------------------------------------------------------------------
# WHY THIS SCRIPT EXISTS — three rules it enforces that prose could not
# ---------------------------------------------------------------------------
#
# 1. NO PIPES ON GRADLE. Every gradle invocation writes to a log FILE and its
#    exit code is captured immediately into a variable. A pipeline's exit status
#    is its LAST command's, so `./gradlew build | tail` reports tail's success
#    and a failed build sails through as green. Worse, a reader that exits early
#    SIGPIPEs gradle mid-task. Both have already happened on this project.
#
# 2. HOW IT ENDED, NOT JUST WHETHER IT ENDED. A tooling crash (a killed worker,
#    a corrupted result store) is neither pass nor fail — it is "no verdict", and
#    reporting it as either is the mistake. The summary distinguishes them.
#
# 3. ONE BUILD ACTOR PER WORKING TREE. Concurrent gradle invocations against one
#    checkout corrupt each other: actor B's `clean` deletes files actor A is
#    mid-write on, producing vanished/truncated files in unrelated subsystems
#    (test-result stores, ktlint caches, compiled classes, packaged jars). Those
#    failures look like product bugs and are not. This script detects other build
#    actors before starting and says so.
#
# Exit code: 0 only if every invocation of every cycle exited 0.

set -u

CYCLES="${1:-1}"
STRICT="${2:-}"
[[ "$CYCLES" =~ ^[0-9]+$ ]] || { echo "usage: $0 [cycles] [--strict]" >&2; exit 2; }

cd "$(dirname "$0")/.." || exit 2
ROOT="$PWD"
LOGDIR="$ROOT/.gate-logs"
rm -rf "$LOGDIR"; mkdir -p "$LOGDIR"

echo "=============================================================="
echo " Gate A — $CYCLES cycle(s)   |   $(date '+%Y-%m-%d %H:%M:%S %z')"
echo " logs: $LOGDIR"
echo "=============================================================="

# ---- rule 3: other build actors -------------------------------------------
foreign=$(pgrep -fl 'GradleWrapperMain|GradleDaemon|KotlinCompileDaemon' 2>/dev/null | grep -vc "^$$ " || true)
if [ "${foreign:-0}" -gt 0 ]; then
  echo
  echo "  !! $foreign gradle/kotlin JVM(s) already running."
  echo "     Gate results are only trustworthy when this tree has ONE build actor."
  echo "     Idle daemons are usually harmless; an ACTIVE build in this tree is not."
  if [ "$STRICT" = "--strict" ]; then
    echo "     --strict given → refusing to run." >&2
    exit 3
  fi
  echo "     (pass --strict to make this fatal)"
  echo
fi

run() { # run <logfile> <args...>  → echoes exit code, never pipes gradle
  local log="$1"; shift
  ./gradlew "$@" > "$log" 2>&1
  echo $?
}

# A crash is not a test failure. Detect the signatures we have actually seen.
crashed() {
  grep -qE 'NoSuchFileException|EOFException|OutOfMemoryError|finished with non-zero exit value|Gradle build daemon disappeared' "$1" 2>/dev/null
}

fails=0; crashes=0
for i in $(seq 1 "$CYCLES"); do
  c=$(run "$LOGDIR/cycle${i}-1-clean.log" clean)
  b=$(run "$LOGDIR/cycle${i}-2-build.log" build)
  n=$(run "$LOGDIR/cycle${i}-3-incremental.log" build)

  status="PASS"
  for pair in "clean:$c:1-clean" "build:$b:2-build" "incremental:$n:3-incremental"; do
    code="${pair#*:}"; code="${code%%:*}"
    name="${pair%%:*}"; logf="$LOGDIR/cycle${i}-${pair##*:}.log"
    if [ "$code" -ne 0 ]; then
      fails=$((fails + 1)); status="FAIL"
      if crashed "$logf"; then
        crashes=$((crashes + 1))
        echo "  cycle $i  $name  EXIT=$code  ** TOOLING CRASH — no verdict, not a test failure **"
        grep -m1 -oE '(NoSuchFileException|EOFException|OutOfMemoryError)[^ ]*' "$logf" | sed 's/^/        /'
      else
        echo "  cycle $i  $name  EXIT=$code  (genuine failure)"
      fi
      echo "        log: $logf"
    fi
  done

  tests=$(find . -path '*/build/test-results/test/TEST-*.xml' 2>/dev/null | wc -l | tr -d ' ')
  echo "  cycle $i  clean=$c build=$b incremental=$n  results=${tests} file(s)  → $status"
done

echo "--------------------------------------------------------------"
if [ "$fails" -eq 0 ]; then
  echo "  GATE A PASS — $CYCLES cycle(s), $((CYCLES * 3)) invocations, 0 failures"
  exit 0
fi
echo "  GATE A FAIL — $fails failed invocation(s), of which $crashes were tooling crashes"
[ "$crashes" -gt 0 ] && echo "  A tooling crash is NOT a red test suite. Re-run before drawing any conclusion,"
[ "$crashes" -gt 0 ] && echo "  and confirm no other build actor was touching this tree."
exit 1
