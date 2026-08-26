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
# After the cycles, two extra stages:
#   1. buildSrc guard tests (Gradle TestKit) — a consumer build only builds
#      buildSrc through its JAR (verified: `build --dry-run` stops at
#      :buildSrc:jar), so its test task NEVER runs automatically; the gate is
#      what makes the COVERAGE_FLOORS / -Pkover.off guard falsifiable on
#      every quality pass (012/F6). Failures are crash-classified like every
#      other stage (013/F3); the tests are forced to execute via cleanTest
#      (013/F4). The stage is ATTEMPTED even when the preflight says
#      offline — a warm TestKit cache passes offline. The fail-soft skip is
#      earned only by a dependency-resolution failure while genuinely
#      offline, evidenced in the stage log OR the JUnit XML results (Gradle's
#      default SHORT console format never prints the exception message where
#      the probes' resolution errors live — 018/F1), with no other genuine
#      test failure among the results (018/F2); a tooling crash classifies as
#      no-verdict BEFORE any skip (018/F2). Skipped stages are counted and
#      named in the summary (014/F4). The verdict branches live in
#      scripts/lib/gate-stages.sh so they are drivable by fixtures.
#   2. scripts/vuln-scan.sh (OSV-Scanner over the committed lockfiles). It
#      fails the gate on real findings and warns without failing when the
#      network is unreachable (fail-soft by design).
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
# 014/F6: `seq 1 0` counts DOWN on BSD seq (prints "1 0"), so a bare `0`
# here would run TWO full cycles instead of zero — it bit the 013 lane
# twice. There is no stages-only mode; run the stages by hand if wanted.
[ "$CYCLES" -ge 1 ] || { echo "usage: $0 [cycles] [--strict] — cycles must be >= 1 (BSD 'seq 1 0' counts down: two cycles, not zero)" >&2; exit 2; }

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

# A crash is not a test failure — gate_crashed() detects the signatures we
# have actually seen (scripts/lib/gate-stages.sh, shared with the buildSrc
# stage below).

fails=0; crashes=0; skips=0

# Stage verdict branches shared with the buildSrc stage below (018/F1+F2):
# sourced so the branches are drivable by fixtures without running the gate.
source "$ROOT/scripts/lib/gate-stages.sh"

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
      if gate_crashed "$logf"; then
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

# ---- buildSrc guard tests (012/F6; 013/F3/F4/F6; 014/F4) ----------------------
# The convention-plugin guards (COVERAGE_FLOORS fail-loud, -Pkover.off skip)
# live in buildSrc, and a main build never executes buildSrc:test — only its
# jar. Run them here so every gate pass exercises them. Same no-pipe rule:
# routed through run() and classified by gate_crashed()/gate_classify_buildsrc
# (scripts/lib/gate-stages.sh) like every other stage
# (013/F3 — the old inline form reported an OOM-killed daemon as a genuine
# failure with the re-run advice suppressed).
#
# cleanTest (013/F4): the gate's `clean` never touches buildSrc (separate
# included build), so a bare `test` is UP-TO-DATE on an unchanged tree and
# the stage would print PASS having executed nothing. cleanTest deletes the
# task's outputs, forcing the guards to RUN on every gate pass; the catalog
# CONTENT is also declared a test input in buildSrc/build.gradle.kts, so a
# bare `-p buildSrc test` re-executes on a libs.versions.toml edit too.
#
# Offline (013/F6 → 014/F4 → 018/F1+F2): the stage is ATTEMPTED regardless
# of the network preflight — the old classification-only skip left the guard
# unexercised whenever the preflight said offline, even with a WARM TestKit
# cache that would have passed offline, and a slow-but-online box (double
# curl-28 → "offline") silently lost the guard too. The TestKit probes
# resolve real dependencies from Maven Central only when their cache is
# cold, so the fail-soft skip is earned ONLY by a resolution failure —
# evidenced in the stage log OR the JUnit XML results, because Gradle's
# default SHORT console format never prints the exception message where the
# probes' resolution errors live (018/F1) — with NO other genuine test
# failure among the results, while the preflight says offline (018/F2); a
# crash classifies as no-verdict BEFORE the skip is considered (018/F2). A
# resolution failure on any other preflight verdict fails the gate loudly
# (consistent with vuln-scan: online-but-unreachable fails, never silently
# skips). A PASS line therefore never hides an unrun guard; skips are
# counted and named. The branches live in gate_classify_buildsrc
# (scripts/lib/gate-stages.sh) and are fixture-driven per release round.
source "$ROOT/scripts/lib/scan-tools.sh"
echo
bnet="$(scan_tools_classify_network gate https://repo.maven.apache.org/maven2/)"
btest=$(run "$LOGDIR/buildsrc-test.log" -p buildSrc cleanTest test)
case "$(gate_classify_buildsrc "$btest" "$LOGDIR/buildsrc-test.log" "$ROOT/buildSrc/build/test-results/test" "$bnet")" in
  pass)
    echo "  buildSrc tests  PASS — COVERAGE_FLOORS / -Pkover.off guards able to fail and to pass"
    ;;
  crash)
    fails=$((fails + 1)); crashes=$((crashes + 1))
    echo "  buildSrc tests  EXIT=$btest  ** TOOLING CRASH — no verdict, not a test failure **"
    grep -m1 -oE '(NoSuchFileException|EOFException|OutOfMemoryError)[^ ]*' "$LOGDIR/buildsrc-test.log" | sed 's/^/        /'
    ;;
  skip)
    skips=$((skips + 1))
    echo "  buildSrc tests  SKIPPED — dependency resolution failed (log or JUnit XML evidence) and the network preflight says offline, with no other genuine test failure (cold TestKit cache; fail-soft by design — re-run online). log: $LOGDIR/buildsrc-test.log"
    ;;
  fail)
    fails=$((fails + 1))
    echo "  buildSrc tests  EXIT=$btest  (genuine failure; log: $LOGDIR/buildsrc-test.log)"
    ;;
esac

# ---- dependency vulnerability scan (OSV-Scanner) -----------------------------
# Scans the committed lockfiles (DEVELOPMENT.md §10.2). Needs network: when
# osv.dev is genuinely unreachable the script exits $SCAN_EXIT_OFFLINE (200,
# defined once in scripts/lib/scan-tools.sh, sourced above) — branched on
# AFTER the exit-code check. Fail-soft BY DESIGN: an offline laptop must not
# fail the gate. Findings exit 1 and scan errors / broken environments exit 2;
# both fail the gate (012/F1: vuln-scan's exit codes are its OWN contract,
# never osv-scanner's propagated raw). The pre-009 grep for a magic log
# string is gone: wording drift would have converted an offline skip into an
# affirmative PASS.
echo
scan=0
./scripts/vuln-scan.sh > "$LOGDIR/vuln-scan.log" 2>&1 || scan=$?
if [ "$scan" -eq 0 ]; then
  echo "  vuln-scan  PASS — no known vulnerabilities in the committed lockfiles"
elif [ "$scan" -eq "$SCAN_EXIT_OFFLINE" ]; then
  skips=$((skips + 1))
  echo "  vuln-scan  SKIPPED — offline (fail-soft by design; log: $LOGDIR/vuln-scan.log)"
else
  fails=$((fails + 1))
  echo "  vuln-scan  EXIT=$scan  (known vulnerabilities or scan error — log: $LOGDIR/vuln-scan.log)"
fi

echo "--------------------------------------------------------------"
# Skipped stages are surfaced in the summary (014/F4): a PASS that
# silently dropped a guard is the failure mode this round fixed.
if [ "$skips" -gt 0 ]; then
  skip_note=", $skips stage(s) skipped (named above)"
else
  skip_note=""
fi
if [ "$fails" -eq 0 ]; then
  echo "  GATE A PASS — $CYCLES cycle(s), $((CYCLES * 3)) invocations, 0 failures${skip_note}"
  exit 0
fi
echo "  GATE A FAIL — $fails failed invocation(s), of which $crashes were tooling crashes${skip_note}"
[ "$crashes" -gt 0 ] && echo "  A tooling crash is NOT a red test suite. Re-run before drawing any conclusion,"
[ "$crashes" -gt 0 ] && echo "  and confirm no other build actor was touching this tree."
exit 1
