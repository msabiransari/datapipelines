#!/usr/bin/env python3
"""ci-name-failing-tests.py — the failing tests by NAME on a CI run's summary page.

    BUILD_OUTCOME=<outcome> GITHUB_STEP_SUMMARY=<file> \\
        python3 scripts/ci-name-failing-tests.py '<glob>' ['<glob>' …]

Called by every job of .github/workflows/ci.yml that runs tests (#669: the `gate` job and
each `browser` shard), so every summary has ONE shape. The globs are quoted — this script
expands them, relative to the working directory — and their union is read, sorted.

A red gate is read from the summary, not by searching a 15,000-line log for FAILED (#290).
Names and messages are escaped for EACH channel they reach (#297): workflow-command escaping
for the annotation, html.escape for the Markdown summary — GitHub's sanitiser drops a
`<WORD>` it reads as a tag, so `expected:<REFUSED> but was:<UNBOUNDED>` would render as
`expected: but was:`. A result file that does not parse (a test JVM killed mid-write — a
step ceiling's own case) is named and skipped, never fatal: the summary is most needed in
exactly that run. Only our own test JVMs' JUnit XML is read (the stdlib parser, as the inline
step it replaces used). BUILD_OUTCOME is the Gradle step's outcome; a failure with no failing
case in the XML is said out loud. Exit 0 (2 with no glob) — it reports; the Gradle step judged.
"""
import glob
import html
import os
import sys
import xml.etree.ElementTree as ET


def esc(s: str) -> str:
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def md(s: str) -> str:
    return html.escape(s, quote=False)


def main(patterns: list[str]) -> int:
    if not patterns:
        print("usage: ci-name-failing-tests.py '<glob>' ['<glob>' …]", file=sys.stderr)
        return 2
    t = f = e = s = n = 0
    failed: list[tuple[str, str, str]] = []
    unreadable: list[tuple[str, str]] = []
    for p in sorted(path for pattern in patterns for path in glob.glob(pattern)):
        try:
            r = ET.parse(p).getroot()
        except (ET.ParseError, OSError) as err:
            unreadable.append((p, str(err)))
            continue
        if r.tag != "testsuite":
            continue
        n += 1; t += int(r.get("tests", 0)); f += int(r.get("failures", 0))
        e += int(r.get("errors", 0)); s += int(r.get("skipped", 0))
        for tc in r.iter("testcase"):
            bad = tc.find("failure")
            if bad is None:
                bad = tc.find("error")
            if bad is not None:
                first = ((bad.get("message") or "").strip().splitlines() or [""])[0][:200]
                failed.append((tc.get("classname", ""), tc.get("name", ""), first))
    more = f" ({len(unreadable)} more unreadable)" if unreadable else ""
    lines = [f"### Tests: {t} in {n} result files{more} — {f} failed, {e} errors, {s} skipped", ""]
    for p, why in unreadable:
        lines.append(f"- unreadable result file: `{md(p)}` — {md(why)}")
        print(f"::warning title=unreadable result file::{esc(p)}")
    for c, name, msg in failed:
        lines.append(f"- **{md(c)}** › {md(name)} — {md(msg)}")
        print(f"::error title=test failed::{esc(c)} > {esc(name)}")
    if not failed and os.environ.get("BUILD_OUTCOME") == "failure":
        lines.append("The build step FAILED with no failing test case in the XML: a non-test task (lint, "
                     "coverage floor, a guard) or a test JVM that died without reporting — read the "
                     "build step's `FAILURE:` lines.")
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as out:
        out.write("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
