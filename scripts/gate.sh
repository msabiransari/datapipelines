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
# After the cycles, three extra stages:
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
#   2. the security-assurance stage (#217, record §10.2 / P1): the entry
#      inventory, the public contract, the isolated-permission witness and the
#      packaged-seam check, run on their own with their own log and verdict
#      line. A class that did not run — no result file, zero tests, a skip —
#      fails the stage (DEVELOPMENT.md §10.2).
#   3. scripts/vuln-scan.sh (OSV-Scanner over the committed lockfiles). It
#      fails the gate on real findings and warns without failing when the
#      network is unreachable (fail-soft by design).
#   4. the docs audit (scripts/docs-audit.sh) over docs/ AND the served manual,
#      which `:modules:mcp-server:docsExport` renders first (242a). CI runs the
#      same pair after its build; a gate without it passed 3da4920a while CI
#      went red on it (2026-09-26) — the stage exists so the two verdicts agree.
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

# GATE_GRADLE_ARGS: extra Gradle arguments appended to every invocation — the
# per-run knobs of DEVELOPMENT.md §9.5 (`-Pdp.test.forks=6 -Pdp.test.forks.e2e=4
# --max-workers=12` is the single-gate profile for a box running ONE gate). Only
# `-P` reaches the project properties: `-D` system properties and
# ORG_GRADLE_PROJECT_* env vars lose to the repo's gradle.properties (measured
# 2026-09-19). Word-split on purpose; quote nothing that needs a space.
read -r -a GATE_EXTRA_ARGS <<< "${GATE_GRADLE_ARGS:-}"
[ "${#GATE_EXTRA_ARGS[@]}" -gt 0 ] && echo " gradle args: ${GATE_EXTRA_ARGS[*]}"

run() { # run <logfile> <args...>  → echoes exit code, never pipes gradle
  local log="$1"; shift
  ./gradlew "$@" "${GATE_EXTRA_ARGS[@]}" > "$log" 2>&1
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

  # -prune .claude: lane worktrees live at .claude/worktrees/<lane> INSIDE the repo, and their
  # build/test-results would otherwise be counted as this tree's.
  tests=$(find . -path ./.claude -prune -o -path '*/build/test-results/test/TEST-*.xml' -print 2>/dev/null | wc -l | tr -d ' ')
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

# ---- security assurance (#217; record §10.2, owner decision P1) ---------------------
# The local merge gate is the enforcement point (P1: direct push), so the security
# aggregate is a stage of it, with a verdict line of its own rather than four classes
# lost among the build's thousands. The build above already ran these classes; this
# stage runs them again ALONE and forced (--rerun: never UP-TO-DATE, so the verdict is
# about this tree), and reads the verdict from the JUnit XML, never from the exit code
# (§9.4). A class with no result file, zero tests or a skip FAILS the stage — a missing
# report is non-passing, not neutral (record §10.2). A tooling crash is classified like
# every other stage (gate_crashed). CI re-runs the same classes cold in `integration`.
echo
SEC_CLASSES=(EntryInventoryE2eTest PublicContractE2eTest PermissionSeamE2eTest PackagedResolverTest)
SEC_RESULTS="$ROOT/tests/integration-tests/build/test-results/test"
sec_args=()
for c in "${SEC_CLASSES[@]}"; do
  rm -f "$SEC_RESULTS/TEST-co.datapipelines.integration.$c.xml"
  sec_args+=(--tests "co.datapipelines.integration.$c")
done
sec=$(run "$LOGDIR/security-assurance.log" :tests:integration-tests:test --rerun "${sec_args[@]}" -x :tests:integration-tests:verifyTestsExecuted)
sec_verdict="$(python3 - "$SEC_RESULTS" "${SEC_CLASSES[@]}" <<'SECPY'
import os, sys, xml.etree.ElementTree as ET
results, classes = sys.argv[1], sys.argv[2:]
tests, problems = 0, []
for c in classes:
    path = os.path.join(results, f"TEST-co.datapipelines.integration.{c}.xml")
    if not os.path.exists(path):
        problems.append(f"{c}: no result file")
        continue
    r = ET.parse(path).getroot()
    n, f, e, s = (int(r.get(k, "0")) for k in ("tests", "failures", "errors", "skipped"))
    tests += n
    if n == 0:
        problems.append(f"{c}: zero tests")
    if f or e:
        problems.append(f"{c}: {f} failed, {e} errors")
    if s:
        problems.append(f"{c}: {s} skipped")
print(("PASS " + str(tests)) if not problems else ("FAIL " + "; ".join(problems)))
SECPY
)"
case "$sec_verdict" in
  PASS*)
    echo "  security-assurance  PASS — ${#SEC_CLASSES[@]} classes, ${sec_verdict#PASS } tests (entry inventory, public contract, isolated-permission witness, packaged seam)"
    ;;
  *)
    fails=$((fails + 1))
    if [ "$sec" -ne 0 ] && gate_crashed "$LOGDIR/security-assurance.log"; then
      crashes=$((crashes + 1))
      echo "  security-assurance  EXIT=$sec  ** TOOLING CRASH — no verdict, not a test failure **"
    else
      echo "  security-assurance  EXIT=$sec  (${sec_verdict#FAIL }; log: $LOGDIR/security-assurance.log)"
    fi
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
  # #193: a skip is a VERDICT THE ORCHESTRATOR MUST READ, never a quiet pass —
  # the script's own WARNING lines (offline classification; and, when the
  # machine-level cache is cold, the missing binary and the URL it would have
  # fetched) are repeated here so the gate output carries the reason itself.
  echo "  vuln-scan  !! SKIPPED — offline (fail-soft by design; NOT a clean result; log: $LOGDIR/vuln-scan.log)"
  grep -F 'WARNING' "$LOGDIR/vuln-scan.log" | sed 's/^/             /' || true
else
  fails=$((fails + 1))
  echo "  vuln-scan  EXIT=$scan  (known vulnerabilities or scan error — log: $LOGDIR/vuln-scan.log)"
fi

# ---- docs audit (the served manual exported first — 242a) --------------------
# scripts/docs-audit.sh checks docs/ AND build/skill-docs/, which
# `:modules:mcp-server:docsExport` renders from the tree under gate. Both
# steps write their own log; the export's failure is its own verdict line.
echo
dexp=$(run "$LOGDIR/docs-export.log" :modules:mcp-server:docsExport -q)
if [ "$dexp" -ne 0 ]; then
  fails=$((fails + 1))
  echo "  docs-audit  EXIT=$dexp  (docsExport failed — log: $LOGDIR/docs-export.log)"
else
  daudit=0
  ./scripts/docs-audit.sh > "$LOGDIR/docs-audit.log" 2>&1 || daudit=$?
  if [ "$daudit" -eq 0 ]; then
    echo "  docs-audit  PASS — $(tail -n 1 "$LOGDIR/docs-audit.log")"
  else
    fails=$((fails + 1))
    echo "  docs-audit  EXIT=$daudit  (log: $LOGDIR/docs-audit.log)"
    grep -vE '^\s*$' "$LOGDIR/docs-audit.log" | tail -n 5 | sed 's/^/             /' || true
  fi
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
