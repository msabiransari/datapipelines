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

# The crash signatures actually observed on this project. A crash is not a test failure:
# it is "no verdict", and reporting it as either pass or fail is the mistake gate_crashed
# detects (gate.sh rule 2). The STRONG two are Gradle's own words for a dead worker or
# daemon and always mean crash. The WEAK three are exception names a test can print too —
# a test whose HTTP client throws EOFException fails with that name in Gradle's FAILED
# block — so each is a crash only when no test reported it (#132, gate_crashed below).
GATE_CRASH_STRONG='finished with non-zero exit value|Gradle build daemon disappeared'
GATE_CRASH_WEAK=(NoSuchFileException EOFException OutOfMemoryError)

# The integration-suite fork decision (#974): the exclusive gate picks its own
# -Pdp.test.forks.e2e= for :tests:integration-tests. Four forks need memory — the floor
# below refuses the upgrade when MemAvailable at the gate's start cannot hold the run.
# Default floor: 1.5 x the gate's own peak footprint (#974 run 2026-11-13T21:07:58Z,
# PASS at four forks): peak summed java RSS 21822 MiB + containers 2261 MiB
# = 24083 MiB; x 1.5 = 36124 -> 36 GB. (Box-wide MemAvailable drift swamped a
# before-minus-lowest read, as in verification-lock.sh.)
GATE_E2E_FORKS_DEFAULT=4
GATE_E2E_MEM_FLOOR_DEFAULT_MB=36864

# gate_crash_signature <log> — exit 0 iff the log carries any of the five signatures.
gate_crash_signature() {
  grep -qE "NoSuchFileException|EOFException|OutOfMemoryError|$GATE_CRASH_STRONG" "${1:-}" 2>/dev/null
}

# gate_test_reported <token> <since-file> <root>... — exit 0 iff a JUnit result file
# (TEST-*.xml directly under a root, under <root>/build/test-results/*/, or under
# <module>/build/test-results/*/ at any depth below the root, never inside build/, src/,
# node_modules/ or a dot-directory) that is NOT OLDER than <since-file> (equal mtime counts,
# as in gate_crash_reports), is a regular file, parses, and holds a <failure> or <error>
# whose message attribute or text contains <token>. A missing marker, no root, a missing
# python3 or an unparseable file is not evidence (exit 1): the caller then calls it a crash.
gate_test_reported() {
  local token="${1:-}" since="${2:-}"
  [ -n "$token" ] && [ -f "$since" ] && [ $# -ge 3 ] || return 1
  shift 2
  command -v python3 > /dev/null 2>&1 || return 1
  python3 - "$token" "$since" "$@" <<'PY' 2>/dev/null
import glob, os, sys
import xml.etree.ElementTree as ET

token, since, roots = sys.argv[1], sys.argv[2], sys.argv[3:]
marker = os.stat(since).st_mtime_ns

def candidates(root):
    yield from glob.glob(os.path.join(glob.escape(root), "TEST-*.xml"))
    for here, dirs, _ in os.walk(root):
        results = os.path.join(here, "build", "test-results")
        if os.path.isdir(results):
            yield from glob.glob(os.path.join(glob.escape(results), "*", "TEST-*.xml"))
        dirs[:] = [d for d in dirs if d not in ("build", "src", "node_modules") and not d.startswith(".")]

for root in roots:
    if not root:
        continue
    for f in candidates(root):
        try:
            if os.path.islink(f) or not os.path.isfile(f) or os.stat(f).st_mtime_ns < marker:
                continue
            tree = ET.parse(f).getroot()
        except (OSError, ET.ParseError):
            continue
        for tc in tree.iter("testcase"):
            for kind in ("failure", "error"):
                for bad in tc.findall(kind):
                    if token in (bad.get("message") or "") or token in (bad.text or ""):
                        sys.exit(0)
sys.exit(1)
PY
}

# gate_crashed <log> [<since-file> <root>...] — exit 0 iff the failed step is a tooling crash.
#   <log>.crashclass non-empty → its word decides (`reported` = not a crash, anything else =
#     crash). gate.sh writes it right after each invocation (gate_after_invocation), because
#     its three build cycles run back to back into the same result directories before any is
#     classified: the next run replaces the XML that would excuse, or a newer XML would
#     falsely excuse, an earlier log.
#   no stored class, <log> alone → the whole-log matcher of old: any signature is a crash.
#   no stored class, marker and roots → the rule, decided now (sound only when nothing ran
#     since the step: pregate, the buildSrc stage's own results directory): a strong
#     signature is a crash; so is each weak token that no fresh failed JUnit XML under the
#     roots carries (gate_test_reported); a log carrying no signature is not a crash.
gate_crashed() {
  local log="${1:-}" since="${2:-}" cls='' tok
  if [ -s "$log.crashclass" ]; then
    IFS= read -r cls < "$log.crashclass" || :
    [ "$cls" != reported ]
    return
  fi
  gate_crash_signature "$log" || return 1
  [ $# -ge 3 ] || return 0
  grep -qE "$GATE_CRASH_STRONG" "$log" 2>/dev/null && return 0
  shift 2
  for tok in "${GATE_CRASH_WEAK[@]}"; do
    grep -qF "$tok" "$log" 2>/dev/null || continue
    gate_test_reported "$tok" "$since" "$@" || return 0
  done
  return 1
}

# gate_after_invocation <rc> <log> <root>... — gate.sh's run() calls it right after each
# Gradle invocation, before the next one starts: drops a stale <log>.crashclass and, when
# <rc> is not 0, stores the step's class there — `crash` or `reported` (not a crash: no
# signature, or every weak token a test reported) — from the rule with <log>.started as the
# marker. Prints nothing and always exits 0; a failed write leaves no file, so gate_crashed
# falls back to the fail-closed whole-log matcher.
gate_after_invocation() {
  local rc="${1:-}" log="${2:-}" cls tmp
  [ -n "$log" ] || return 0
  rm -f "$log.crashclass" 2> /dev/null || :
  [ "$rc" != 0 ] || return 0
  shift 2
  if gate_crashed "$log" "$log.started" "$@"; then cls=crash; else cls=reported; fi
  tmp="$log.crashclass.$$"
  { printf '%s\n' "$cls" > "$tmp" && mv -f "$tmp" "$log.crashclass"; } 2> /dev/null || rm -f "$tmp" 2> /dev/null || :
  return 0
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
  elif gate_crashed "$log" "$since" "$root/modules" "$root/tests" "$root/buildSrc"; then
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
# resolution errors; gate_crashed with <log>.started and <results-dir>, so a
# crash word a fresh failed JUnit XML carries is not one, #132); a log that
# carries a crash signature but is NOT a crash (a test reported the word)
# fails next and never reaches the skip — the resolution-only filter below
# would otherwise discount a failure such as "Could not GET resource:
# java.io.EOFException", which was `crash` (counted) before #132; the skip
# is earned ONLY when the preflight says offline AND resolution failed (log
# or JUnit XML) AND no test failed for a non-resolution reason; everything
# else fails loudly — including a resolution failure on any other preflight
# verdict (online-but-unreachable fails, never silently skips; consistent
# with vuln-scan).
gate_classify_buildsrc() {
  local rc="$1" log="$2" results="$3" net="$4"
  if [ "$rc" -eq 0 ]; then
    echo pass
  elif gate_crashed "$log" "$log.started" "$results"; then
    echo crash
  elif gate_crash_signature "$log"; then
    echo fail
  elif [ "$net" = "offline" ] \
    && gate_buildsrc_resolution_failed "$log" "$results" \
    && [ "$(gate_buildsrc_genuine_failures "$results")" -eq 0 ]; then
    echo skip
  else
    echo fail
  fi
}

# gate_e2e_mem_floor_mb — prints the integration-fork floor in MB; garbage falls back
# to the default with a stderr line (same shape as verification::_mem_floor_mb).
gate_e2e_mem_floor_mb() {
  local raw="${GATE_E2E_MEM_FLOOR_MB-}"
  if [ -z "$raw" ]; then
    printf '%s\n' "$GATE_E2E_MEM_FLOOR_DEFAULT_MB"
  elif [[ "$raw" =~ ^[0-9]{1,9}$ ]]; then
    printf '%s\n' "$((10#$raw))"
  else
    echo "gate: GATE_E2E_MEM_FLOOR_MB='$raw' is not an integer of at most 9 digits; using the default ${GATE_E2E_MEM_FLOOR_DEFAULT_MB} MB" >&2
    printf '%s\n' "$GATE_E2E_MEM_FLOOR_DEFAULT_MB"
  fi
}

# gate_e2e_gb <mb> — whole GB, rounded up (the repo's 1 GB = 1024 MB convention,
# as in verification-lock.sh's floor comment).
gate_e2e_gb() {
  printf '%s\n' "$(( (10#${1:-0} + 1023) / 1024 ))"
}

# gate_e2e_fork_args <mem_available_mb> <floor_mb> <raw_forks> [caller args...] — pure
# decision for the integration-suite fork count (#974). Prints on stdout the ONE Gradle
# argument to append (empty stdout = append nothing) and ALWAYS a stderr header line,
# with a leading space like gate.sh's other header lines. Every branch returns 0: the
# decision never reds the gate.
#   - Caller wins first (#974): any caller arg carrying -Pdp.test.forks.e2e= leaves
#     nothing to append — GATE_GRADLE_ARGS beats the gate's automatic choice.
#   - <raw_forks>: empty means GATE_E2E_FORKS_DEFAULT silently; garbage or below 1
#     falls back to it with ONE stderr line.
#   - <mem_available_mb> unreadable, or below <floor_mb>: nothing appended, the header
#     says why — a short machine gives a slower gate, never a red one.
#   - Otherwise: -Pdp.test.forks.e2e=<forks>.
gate_e2e_fork_args() {
  local mem="$1" floor="$2" raw="$3"
  shift 3
  local arg
  for arg in "$@"; do
    if [[ "$arg" == *-Pdp.test.forks.e2e=* ]]; then
      echo " integration forks: caller-set (GATE_GRADLE_ARGS pins dp.test.forks.e2e)" >&2
      return 0
    fi
  done
  local forks="$GATE_E2E_FORKS_DEFAULT"
  if [ -n "$raw" ]; then
    if [[ "$raw" =~ ^[0-9]{1,9}$ ]] && [ "$((10#$raw))" -ge 1 ]; then
      forks="$((10#$raw))"
    else
      echo "gate: GATE_E2E_FORKS='$raw' is not an integer of at least 1; using the default ${GATE_E2E_FORKS_DEFAULT}" >&2
    fi
  fi
  if [[ ! "$mem" =~ ^[0-9]{1,9}$ ]]; then
    echo " integration forks: repository default (MemAvailable unreadable, floor $(gate_e2e_gb "$floor") GB)" >&2
    return 0
  fi
  if [ "$((10#$mem))" -lt "$((10#$floor))" ]; then
    echo " integration forks: repository default (MemAvailable $(gate_e2e_gb "$mem") GB below floor $(gate_e2e_gb "$floor") GB)" >&2
    return 0
  fi
  echo " integration forks: ${forks} (MemAvailable $(gate_e2e_gb "$mem") GB, floor $(gate_e2e_gb "$floor") GB)" >&2
  printf -- '-Pdp.test.forks.e2e=%s\n' "$forks"
}
