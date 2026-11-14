#!/usr/bin/env bash
#
# test-recount.sh — the mechanical test recount, in ONE place so every lane's
# numbers are the same numbers.
#
#   ./scripts/test-recount.sh
#   → files=298 tests=3155 failures=0 errors=0 skipped=1 unreadable=0
#
#   ./scripts/test-recount.sh <results-root>
#   → the same counts over that root's own modules/* and tests/* build/test-results
#     trees, plus TEST-*.xml files directly at the root (flat CI artifacts).
#     A browser-shard artifact uses its upload paths' common build directory as
#     its root, so test-results/**/*.xml directly under that root is read too.
#     Never walk recursively from the root: sibling worktrees and saved snapshots
#     contain duplicate results. #441: the
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
#   ./scripts/test-recount.sh <root> <root> …
#   → ONE counts line over every root (#669): CI's `gate-reports` plus one
#     `browser-reports-<i>` per browser shard, each downloaded into its own directory
#     (the recipe is DEVELOPMENT.md §9.4). Each root is read exactly as a single root is.
#     A TEST-*.xml file name found under two roots is refused (exit 4, each one named):
#     the same class would be counted twice — a shard overlap, or one artifact passed
#     twice. A root with no result XML is named and exits 3. Unreadable files are named
#     with their root. Zero and one root behave exactly as before. Never pass the
#     pre-gate's stage roots together: they overlap by design (above), and exit 4 says so.
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
# Exit 0 whatever the counts say, 2 for a root that is not a directory,
# 3 for a root with no result XML at all, 4 for a result file name under two
# roots (nothing counted) — it REPORTS, it does not judge.
# Read the numbers.
#
# A result file that does not parse (a test JVM killed mid-write, a disk-full,
# a daemon OOM) is counted and NAMED, never a crash (#309): the counts line
# ends with `unreadable=N`, each unreadable path follows on its own line with
# the parse error — the same shape CI's summary step prints. A named file is a
# tooling event the reader must see; dropping it silently would under-report
# the very total this script exists to keep honest.

set -u
# #441: optional results roots (the saved pre-gate stage directory; #669: several, summed). No
# argument keeps today's repo-root behavior exactly; an argument that is missing or not a
# directory is refused rather than reported as an empty success.
if [ "$#" -ge 1 ]; then
  for root in "$@"; do
    if [ ! -d "$root" ]; then
      echo "test-recount.sh: results root '$root' is not a directory — nothing recounted" >&2
      exit 2
    fi
  done
else
  cd "$(dirname "$0")/.." || exit 2
  set -- "$(pwd)"
fi

python3 - "$@" <<'PY'
import glob
import os
import sys
import xml.etree.ElementTree as ET
roots = sys.argv[1:]
t=f=e=s=n=0
unreadable: list[tuple[str, str]] = []
per_root: list[tuple[str, list[str]]] = []
for root in roots:
    rel = (glob.glob("modules/*/build/test-results/**/*.xml", root_dir=root, recursive=True) +
           glob.glob("tests/*/build/test-results/**/*.xml", root_dir=root, recursive=True) +
           glob.glob("test-results/**/*.xml", root_dir=root, recursive=True) +
           glob.glob("TEST-*.xml", root_dir=root))
    per_root.append((root, rel))
# #669: one class's result file under two roots would be counted twice — refuse, count nothing.
if len(roots) > 1:
    # Keyed by the root's POSITION, not its spelling: the same root passed twice is an overlap.
    seen: dict[str, int] = {}
    twice: list[str] = []
    for i, (root, rel) in enumerate(per_root):
        for p in rel:
            name = os.path.basename(p)
            if name.startswith("TEST-") and name in seen and seen[name] != i:
                twice.append(f"{name} under both '{roots[seen[name]]}' and '{root}'")
            seen.setdefault(name, i)
    if twice:
        for why in twice:
            print(f"test-recount.sh: duplicate result file {why}", file=sys.stderr)
        print(f"test-recount.sh: {len(twice)} result file(s) under two roots — nothing recounted", file=sys.stderr)
        sys.exit(4)
for root, rel in per_root:
    for p in rel:
        shown = p if len(roots) == 1 else os.path.join(root, p)
        try:
            r = ET.parse(os.path.join(root, p)).getroot()
            if r.tag != "testsuite": continue
            tests = int(r.get("tests", 0))
            failures = int(r.get("failures", 0))
            errors = int(r.get("errors", 0))
            skipped = int(r.get("skipped", 0))
        except (ET.ParseError, OSError, ValueError) as err:
            unreadable.append((shown, str(err)))
            continue
        n+=1; t+=tests; f+=failures; e+=errors; s+=skipped
print(f"files={n} tests={t} failures={f} errors={e} skipped={s} unreadable={len(unreadable)}", flush=True)
for p, why in unreadable:
    print(f"unreadable result file: `{p}` — {why}")
empty = [root for root, rel in per_root if not rel]
for root in empty:
    print(f"test-recount.sh: no test result XML under '{root}' "
          "(modules/*/…, tests/*/…, or TEST-*.xml at the root)", file=sys.stderr)
if empty:
    sys.exit(3)
PY
