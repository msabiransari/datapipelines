#!/usr/bin/env bash
#
# test-recount.sh — the mechanical test recount, in ONE place so every lane's
# numbers are the same numbers.
#
#   ./scripts/test-recount.sh
#   → files=298 tests=3155 failures=0 errors=0 skipped=1
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

set -u
cd "$(dirname "$0")/.." || exit 2

python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
t=f=e=s=n=0
for p in glob.glob("modules/*/build/test-results/**/*.xml", recursive=True) + \
         glob.glob("tests/*/build/test-results/**/*.xml", recursive=True):
    r = ET.parse(p).getroot()
    if r.tag != "testsuite": continue
    n+=1; t+=int(r.get("tests",0)); f+=int(r.get("failures",0))
    e+=int(r.get("errors",0)); s+=int(r.get("skipped",0))
print(f"files={n} tests={t} failures={f} errors={e} skipped={s}")
PY
