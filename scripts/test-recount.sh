#!/usr/bin/env bash
#
# test-recount.sh — the mechanical test recount, in ONE place so every lane's
# numbers are the same numbers.
#
#   ./scripts/test-recount.sh
#   → files=298 tests=3155 failures=0 errors=0 skipped=1 unreadable=0
#
#   ./scripts/test-recount.sh <results-root>
#   → the same counts over that root's own modules/* and tests/* trees. #441: the
#     pre-gate snapshots each stage's XML under .pregate-logs/runs/<run-id>/<stage>/, so
#     read ONE stage at a time:
#
#       ./scripts/test-recount.sh .pregate-logs/runs/<run-id>/2
#       ./scripts/test-recount.sh .pregate-logs/runs/<run-id>/3
#
#     A stage root yields ONLY that stage's inventory. Guard classes can run in more than
#     one stage, so the stages are never summed into a purported unique-test total.
#     An explicit root that is not a directory is refused (exit 2) rather than reported
#     as an empty success.
#
# ---------------------------------------------------------------------------
# WHY THIS SCRIPT EXISTS
# ---------------------------------------------------------------------------
#
# 1. A BUILD'S EXIT CODE IS NOT A TEST VERDICT. `./gradlew build … ; echo $?`
#    reports the shell's last command, a pipe reports the pipe's last stage,
#    and a `--continue` build can end non-zero for a lint task while every
#    test passed. The verdict comes from the JUnit XML the tests themselves
#    wrote, and nothing else. (DEVELOPMENT.md §9.4, scripts/gate.sh rule 1.)
#
# 2. THE GLOB HAS TO COVER BOTH TREES. `modules/*/build/test-results/` alone
#    silently omits `tests/*/` — the cross-module integration suite — and
#    under-reported the total for four consecutive rounds. Every occurrence
#    was a correct pass/fail with a wrong total, which is exactly the kind of
#    error nobody notices until two lanes disagree.
#
# 3. PROSE ASKING FOR A RECOUNT HAS FAILED; A COMMAND HAS NOT. Shipping the
#    executable form instead of a warning about it fixed the under-reporting
#    on first use and has held every round since. This file is that command,
#    kept where the recipe (DEVELOPMENT.md §9.4) can point at it rather than
#    reprint it.
#
# Reads only build output; writes nothing; needs python3 and no network.
# Exit code is 0 whatever the counts say — it REPORTS, it does not judge.
# Read the numbers.
#
# A result file that does not parse (a test JVM killed mid-write, a disk-full,
# a daemon OOM) is counted and NAMED, never a crash (#309): the counts line
# ends with `unreadable=N`, each unreadable path follows on its own line with
# the parse error — the same shape CI's summary step prints. A named file is a
# tooling event the reader must see; dropping it silently would under-report
# the very total this script exists to keep honest.

set -u
# #441: an optional results root (the saved pre-gate stage directory). No argument keeps
# today's repo-root behavior exactly; an argument that is missing or not a directory is
# refused rather than reported as an empty success.
if [ "$#" -ge 1 ]; then
  root="$1"
  if [ ! -d "$root" ]; then
    echo "test-recount.sh: results root '$root' is not a directory — nothing recounted" >&2
    exit 2
  fi
  cd "$root" || exit 2
else
  cd "$(dirname "$0")/.." || exit 2
fi

python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
t=f=e=s=n=0
unreadable=[]
for p in glob.glob("modules/*/build/test-results/**/*.xml", recursive=True) + \
         glob.glob("tests/*/build/test-results/**/*.xml", recursive=True):
    try:
        r = ET.parse(p).getroot()
    except (ET.ParseError, OSError) as err:
        unreadable.append((p, str(err)))
        continue
    if r.tag != "testsuite": continue
    n+=1; t+=int(r.get("tests",0)); f+=int(r.get("failures",0))
    e+=int(r.get("errors",0)); s+=int(r.get("skipped",0))
print(f"files={n} tests={t} failures={f} errors={e} skipped={s} unreadable={len(unreadable)}")
for p, why in unreadable:
    print(f"unreadable result file: `{p}` — {why}")
PY
