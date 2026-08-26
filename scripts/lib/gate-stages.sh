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
