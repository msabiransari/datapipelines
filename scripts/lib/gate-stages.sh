#!/usr/bin/env bash
# scripts/lib/gate-stages.sh — stage classifiers for scripts/gate.sh, SOURCED,
# not executed (same pattern as lib/scan-tools.sh).
#
# Why a lib (018/F1+F2): the buildSrc stage's verdict branches lived inline in
# gate.sh and were judged by reading, not execution — and the offline fail-soft
# skip's console-log grep could never fire for its own scenario: with Gradle's
# default SHORT exception format, the TestKit probes' resolution failure text
# surfaces only inside the JUnit failure MESSAGES, which SHORT never prints.
# Extracted here so every branch is mechanically drivable by fixtures: a
# branch that was not driven is a claim, not a fix.

# gate_crashed <file> — the crash signatures actually observed on this
# project. A crash is not a test failure: it is "no verdict", and reporting
# it as either pass or fail is the mistake this detects (gate.sh rule 2).
gate_crashed() {
  grep -qE 'NoSuchFileException|EOFException|OutOfMemoryError|finished with non-zero exit value|Gradle build daemon disappeared' "$1" 2>/dev/null
}

# ---- HotSpot error reports of crashed test JVMs (#773, DEVELOPMENT.md §9.4) ----------------
# Since #685 every test JVM of a project applying the conventions carries
# -XX:ErrorFile=<module>/build/hs_err/hs_err_pid%p.log, so a worker HotSpot's own handler
# kills (exit value 134) leaves its report there. These two functions are the ONE
# definition both scripts/gate.sh and scripts/pregate.sh call (pregate sources this lib).

# A report line is printed, never the report: it also carries the environment variables
# and the command line. Bounded to printable ASCII and 240 columns.
gate_crash_line() {
  printf '%s' "$1" | LC_ALL=C tr -cd '\11\40-\176' | cut -c1-240
}

# gate_crash_reports <since-file> <root> — prints, for every
# <root>/modules/*/build/hs_err/hs_err_pid*.log and <root>/tests/*/build/hs_err/hs_err_pid*.log
# NOT OLDER than <since-file> (the step's own start marker, created immediately before the
# step ran), the module, the report path, the first `#  SIG…` / `#  Internal Error` /
# `#  EXCEPTION_…` line and the line after `# Problematic frame:` — those lines only.
# `! -ot` admits an EQUAL mtime on purpose: the kernel stamps files from a coarse clock, so
# a marker and a report written in the same tick carry the same mtime, which `find -newer`
# (strictly newer) would drop. Reports older than the marker are counted, never attributed,
# and never deleted (CI's gate-reports artifact and a human may still want them). Prints
# nothing when there are none. Exit 0 iff at least one report is this step's.
gate_crash_reports() {
  local since="$1" root="$2" f rel module sig frame new=0 old=0
  if [ ! -f "$since" ]; then
    echo "crash reports: the step's start marker $since is missing — no report attributed"
    return 1
  fi
  for f in "$root"/modules/*/build/hs_err/hs_err_pid*.log "$root"/tests/*/build/hs_err/hs_err_pid*.log; do
    [ -f "$f" ] && [ ! -L "$f" ] || continue
    if [ "$f" -ot "$since" ]; then old=$((old + 1)); continue; fi
    new=$((new + 1))
    rel="${f#"$root"/}"
    module="${rel#*/}"; module="${module%%/*}"
    sig="$(grep -m1 -E '^#  (SIG[A-Z]+|Internal Error|EXCEPTION_)' "$f")"
    frame="$(grep -m1 -A1 -F '# Problematic frame:' "$f" | sed -n 2p)"
    echo "crash report  module=$(gate_crash_line "$module")  path=$(gate_crash_line "$rel")"
    echo "  $(gate_crash_line "${sig:-(no signal line in the report)}")"
    echo "  $(gate_crash_line "${frame:-(no Problematic frame line in the report)}")"
  done
  [ "$old" -eq 0 ] || echo "older reports exist, not from this step: $old"
  [ "$new" -gt 0 ]
}

# gate_crash_explain <step-log> <since-file> <root> — what a failed step prints about a
# crashed test worker: "CRASHED, not a test failure" when Gradle reports a worker's
# non-zero exit value, the step's reports (gate_crash_reports), and — when the log carries a
# crash signature but no report is the step's — why none exists. A step with no crash
# signature and no report prints nothing (besides the older-reports count, when any exist).
gate_crash_explain() {
  local log="$1" since="$2" root="$3" worker code
  worker="$(grep -m1 -oE 'finished with non-zero exit value [0-9]+' "$log" 2>/dev/null)"
  code="${worker##* }"
  [ -z "$worker" ] || echo "a test worker CRASHED (exit value $code) — not a test failure (see DEVELOPMENT.md §9.4)"
  gate_crash_reports "$since" "$root" && return 0
  if [ -n "$worker" ]; then
    case "$code" in
      139) echo "no HotSpot report — an unhandled SIGSEGV (exit 139) leaves none; DEVELOPMENT.md §9.4" ;;
      134) echo "no HotSpot report — exit 134 is HotSpot's own abort, which writes one under build/hs_err only for a module applying the conventions' -XX:ErrorFile (else the JVM's working directory); DEVELOPMENT.md §9.4" ;;
      *) echo "no HotSpot report — only HotSpot's own abort (exit 134) writes one; exit $code is $([ "$code" -gt 128 ] && echo "signal $((code - 128))" || echo "the worker's own exit status"); DEVELOPMENT.md §9.4" ;;
    esac
  elif gate_crashed "$log"; then
    echo "no HotSpot report — the crash signature in the log is not a test-JVM abort (daemon, memory or file store); DEVELOPMENT.md §9.4"
  fi
  return 0
}

# gate_buildsrc_resolution_failed <log> <results-dir> — true when the stage
# hit a dependency-resolution failure, evidenced in the CONSOLE LOG or in the
# JUnit XML results under <results-dir> (018/F1: Gradle's default SHORT
# exception format prints only the exception class at the failure site — the
# message, where the TestKit probes' resolution errors live
# (AssertionFailedError / UnexpectedBuildFailure message text), is carried by
# the XML. A cold TestKit cache on an offline laptop produces exactly that
# shape; the old log-only grep saw a "genuine failure" instead of the skip it
# was written to grant.)
gate_buildsrc_resolution_failed() {
  local log="$1" results="$2"
  grep -qE 'Could not resolve|Could not GET|Could not HEAD' "$log" 2>/dev/null && return 0
  ls "$results"/TEST-*.xml > /dev/null 2>&1 || return 1
  grep -qE 'Could not resolve|Could not GET|Could not HEAD' "$results"/TEST-*.xml 2>/dev/null
}

# gate_buildsrc_genuine_failures <results-dir> — prints the count of failed
# test cases whose failure text carries NO resolution-error evidence. The
# fail-soft skip must not fire when even one test failed for a real reason
# (018/F2: offline + a genuine guard red is a red gate, and a log that merely
# HAPPENS to contain "Could not resolve" must not paint over it).
#   no results dir      → 0  (tests never ran: resolution failed earlier)
#   unparseable XML     → skipped (half-written results from a crash are not
#                         evidence of a verdict either way)
#   python3 unavailable → 1  (cannot analyze → refuse to skip; fail-soft is
#                         earned by inspection, not assumed)
gate_buildsrc_genuine_failures() {
  local dir="$1"
  [ -d "$dir" ] || { echo 0; return 0; }
  command -v python3 > /dev/null 2>&1 || { echo 1; return 0; }
  python3 - "$dir" <<'PY' || echo 1
import glob, re, sys
import xml.etree.ElementTree as ET

pat = re.compile(r"Could not resolve|Could not GET|Could not HEAD")
genuine = 0
for f in sorted(glob.glob(sys.argv[1] + "/TEST-*.xml")):
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    for tc in root.iter("testcase"):
        for kind in ("failure", "error"):
            for bad in tc.findall(kind):
                text = " ".join(x for x in (bad.get("message"), bad.text) if x)
                if not pat.search(text):
                    genuine += 1
print(genuine)
PY
}

# gate_classify_buildsrc <rc> <log> <results-dir> <net-verdict> — prints
# exactly one of: pass | crash | skip | fail. The branch ORDER is the
# contract (018/F2): a tooling crash classifies before anything else (an
# OOM/daemon death is "no verdict, re-run" even when the same log also holds
# resolution errors); the skip is earned ONLY when the preflight says
# offline AND resolution failed (log or JUnit XML) AND no test failed for a
# non-resolution reason; everything else fails loudly — including a
# resolution failure on any other preflight verdict (online-but-unreachable
# fails, never silently skips; consistent with vuln-scan).
gate_classify_buildsrc() {
  local rc="$1" log="$2" results="$3" net="$4"
  if [ "$rc" -eq 0 ]; then
    echo pass
  elif gate_crashed "$log"; then
    echo crash
  elif [ "$net" = "offline" ] \
    && gate_buildsrc_resolution_failed "$log" "$results" \
    && [ "$(gate_buildsrc_genuine_failures "$results")" -eq 0 ]; then
    echo skip
  else
    echo fail
  fi
}
