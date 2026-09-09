#!/usr/bin/env bash
# docs-audit.sh — mechanical consistency guard for the spec set.
#
# Checks (all must pass; exit 1 on any failure):
#   A. Cross-reference anchors: every](file.md#anchor) link resolves to a real
#      heading in the target file (GitHub slug rules).
#   B. Config-key authority: every `datapipelines.*` dotted key used anywhere
#      is defined in docs/configuration.md (metric names defined in
#      docs/observability.md are also allowed).
#   C. Error-code catalog: every error code used anywhere exists in
#      docs/pipeline-contract.md (§12/§13, the single catalog). Audit-event
#      names from enums.md §15 are exempt (events, not error codes), and so are
#      STRUCTURED LOG event names defined in observability.md §3.4A — a log line's
#      `event=` is neither an error code nor an audit row, and observability.md is
#      its authority the same way it is for metric names in check B.
#   D. Forbidden legacy spellings (renamed/removed in the 2026-08 campaign, and the
#      pre-075/pre-081 deployment names) outside Change Log sections. Check D alone
#      also scans the non-`docs/` files a deployer actually copies from — README.md,
#      deploy/**, app.sh, scripts/*.sh — because a stale `deploy/.env` in a compose
#      header misleads exactly as much as one in a spec, and 081's whole job was
#      deleting files whose names are still typed from memory.
#
# docs/SPEC-REVIEW-2026-08.md is exempt from B–D: it is the historical record
# of the old spellings. Change Log sections are exempt from D for the same
# reason. DB_CLOSE_DELAY is only flagged inside a JDBC URL — prose explaining
# why the flag is absent is deliberate and allowed.
#
# A doc whose Status line reads `design (not yet normative` is exempt from C
# ONLY: a design doc PROPOSES error codes, and the code lands in §13 with the
# implementation, not with the proposal (MISTAKES.md — a catalogued code split
# from its constant leaves main red between the two). The exemption is keyed on
# the doc's own Status line, so it evaporates the moment the doc goes normative
# and the audit then demands the catalog rows. Exempted docs are printed on
# every run so the debt stays visible rather than silent.
#
# Born 2026-08-07 (SPEC-REVIEW-2026-08 Phase 3). Baseline: exit 0 on the
# v1.1–v1.3 spec set; self-test: scripts/docs-audit.sh --self-test doctors a
# temp copy and must exit 1.

set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${1:-}" == "--self-test" ]]; then
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  cp -R docs DEVELOPMENT.md scripts README.md app.sh "$tmp/"
  mkdir -p "$tmp/deploy/env" "$tmp/deploy/sample-data"
  cp deploy/*.yml deploy/secrets.env.example "$tmp/deploy/"
  cp deploy/env/*.env "$tmp/deploy/env/"
  cp deploy/sample-data/*.sh "$tmp/deploy/sample-data/"
  # Introduce one defect per check class. The heading first CLOSES staging.md's Change
  # Log section (the doc's last section): checks C and D exempt Change Log lines, so
  # defects appended bare at EOF were placebo — the self-test passed on A and B alone
  # while a dead C or D check would have gone unnoticed (found 2026-09-02, 051 T28:
  # the newly-added workspace defect didn't fail until this heading existed).
  {
    echo '## Self-test probe section (never shipped)'
    echo '[bad link](pipeline-contract.md#no-such-section)'
    echo 'Uses `datapipelines.no.such-key` here.'
    echo 'Raises `pipeline.validation.nonexistent_code` here.'
    echo 'Raises `workspace.nonexistent_code` here.'
    echo 'Legacy `terminal_node_id` mention.'
  } >> "$tmp/docs/staging.md"
  # D over the NON-docs scan set, which checks A-C never reach: a header that still
  # sends a deployer to a file 081 deleted must fail exactly like a stale spec line.
  echo '# see deploy/env/posture/development.env' >> "$tmp/deploy/compose.yml"
  if (cd "$tmp" && bash scripts/docs-audit.sh >/dev/null 2>&1); then
    echo "SELF-TEST FAILED: doctored docs passed the audit" >&2; exit 1
  else
    echo "self-test OK: doctored docs correctly fail the audit"; exit 0
  fi
fi

python3 - <<'PY'
import re, sys, glob, os

DOCS = sorted(glob.glob("docs/*.md")) + ["DEVELOPMENT.md"]
# This script IS the catalogue of forbidden spellings, so it names every one of them;
# and the spec review is the historical record of the old ones.
EXEMPT_HISTORY = {"docs/SPEC-REVIEW-2026-08.md", "scripts/docs-audit.sh"}
failures = []

def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()

texts = {p: read(p) for p in DOCS}

# Docs that declare themselves not-yet-normative may propose error codes the
# catalog does not carry yet (check C only — B and D still apply in full).
NOT_YET_NORMATIVE = re.compile(r"^\*\*Status:\*\*\s*design \(not yet normative", re.M)
EXEMPT_PROPOSED = {p for p, t in texts.items() if NOT_YET_NORMATIVE.search(t)}

def gh_slug(heading):
    h = heading.strip().lower()
    h = re.sub(r"`", "", h)
    out = []
    for ch in h:
        if ch.isalnum() or ch in "_- ":
            out.append(ch)
        # other chars (., :, (, ), ?, ↔, —, /, |, ⊂ …) are dropped
    return "".join(out).replace(" ", "-")

# Heading slugs per file (with GitHub duplicate suffixing)
slugs = {}
for p, t in texts.items():
    seen, s = {}, set()
    for m in re.finditer(r"^#{1,6}\s+(.+?)\s*$", t, re.M):
        base = gh_slug(m.group(1))
        n = seen.get(base, 0)
        s.add(base if n == 0 else f"{base}-{n}")
        seen[base] = n + 1
    slugs[p] = s

# ---- A. cross-reference anchors -------------------------------------------
for p, t in texts.items():
    for m in re.finditer(r"\]\(([A-Za-z0-9._\-]+\.md)(#[^)\s]+)?\)", t):
        target, anchor = m.group(1), m.group(2)
        tpath = os.path.join(os.path.dirname(p) or ".", target)
        tpath = os.path.normpath(tpath)
        if tpath == "README.md" and p == "DEVELOPMENT.md":
            tpath = "docs/README.md"
        if tpath not in texts and not os.path.exists(tpath):
            failures.append(f"A {p}: link target missing: {target}")
            continue
        if anchor:
            tkey = tpath if tpath in texts else None
            tslugs = slugs.get(tkey, set())
            if tkey and anchor[1:] not in tslugs:
                failures.append(f"A {p}: dead anchor {target}{anchor}")

# ---- helpers for B/C -------------------------------------------------------
def strip_fences(t):
    """Remove fenced code blocks (Kotlin/SQL/YAML snippets are not doc claims)."""
    return re.sub(r"^```.*?^```", "", t, flags=re.M | re.S)

def body_lines(t):
    """Lines outside Change Log sections and outside code fences."""
    in_changelog = in_fence = False
    for i, line in enumerate(t.splitlines(), 1):
        if line.startswith("```"):
            in_fence = not in_fence
            continue
        if re.match(r"^#{1,6}\s", line):
            in_changelog = bool(re.search(r"change\s*log", line, re.I))
        if not in_changelog and not in_fence:
            yield i, line

# ---- B. config-key authority ----------------------------------------------
# (?<![.\w]) rejects package names like co.datapipelines.datasources.crypto
KEY_RE = r"(?<![.\w])datapipelines(?:\.[a-z0-9][a-z0-9-]*)+"
defined = set()
for src in ("docs/configuration.md", "docs/observability.md"):
    if src in texts:
        defined |= set(re.findall(KEY_RE, texts[src]))

def key_ok(k):
    if k in defined:
        return True
    return any(d.startswith(k + ".") for d in defined) or any(k.startswith(d + ".") for d in defined)

for p, t in texts.items():
    if p in EXEMPT_HISTORY or p in ("docs/configuration.md", "docs/observability.md"):
        continue
    for k in sorted(set(re.findall(KEY_RE, strip_fences(t)))):
        if not key_ok(k):
            failures.append(f"B {p}: config key not defined in configuration.md: {k}")

# ---- C. error-code catalog -------------------------------------------------
# Lookbehind rejects sub-paths of longer dotted names (spring.datasource.url,
# datapipelines.auth.*); lookahead rejects hyphen-continuations (result.ttl-min).
# `endpoint` joined in 074 on BOTH sides: the alternation below (so endpoint.* error
# codes are checked against §13.14) and the §15 event extraction (so the endpoint.*
# AUDIT events are recognised as events rather than demanded as catalog codes).
# `mcp` joined the alternation in 052 so the mcp.tool.* audit events are checked
# against Enums §15 exactly like auth.*/datasource.* events — before that the
# prefix was invisible to check C and a doc could name an unregistered mcp.*
# event freely. `workspace` joined in 051 (T28) for the same reason: the §13.12
# CRUD codes (workspace.in_use, workspace.creation_forbidden, ...) are a real
# catalog domain, and without the prefix a misspelt workspace.* code in any doc
# passed the mechanical audit — only the drift tests would have caught it.
CODE_RE = (r"(?<![.\w-])(?:pipeline|template|datasource|auth|workspace|result|rate_limit|"
           r"idempotency|type_mapping|mcp|endpoint)\.[a-z0-9_]+(?:\.[a-z0-9_*]+)*(?![\w-])")
catalog = set(re.findall(CODE_RE, texts["docs/pipeline-contract.md"]))
# datasource.validation.* is delegated: pipeline-contract §13.8 names Datasources §9
# as the defining list, so codes defined there join the catalog.
catalog |= {c for c in re.findall(CODE_RE, texts.get("docs/datasources.md", ""))
            if c.startswith("datasource.validation.")}
# audit events (enums §15) are events, not error codes — extract only from §15.
# `pipeline` and `template` joined the event extraction in 101: the version-lifecycle
# audit events (`pipeline.version.discarded`, `template.purged`, …) live in §15's
# version-lifecycle sub-table exactly like the auth.*/datasource.*/mcp.* events — the
# endpoint/074 and mcp/052 precedent. They remain distinguishable from error codes
# because extraction is §15-only: a `pipeline.version.*` NAME still has to be defined
# in §15 before any doc may cite it, and §13 catalog codes are unaffected.
enums_txt = texts.get("docs/enums.md", "")
sec15 = re.search(r"^## 15\..*?(?=^## 16\.)", enums_txt, re.M | re.S)
events = set(re.findall(r"(?:auth|datasource|mcp|endpoint|pipeline|template)\.[a-z_]+(?:\.[a-z_]+)*",
                        sec15.group(0) if sec15 else enums_txt))
# auth.* events are also cited outside §15 (auth.md §10.1 etc.)
events |= set(re.findall(r"auth\.[a-z_]+(?:\.[a-z_]+)*", enums_txt))
# STRUCTURED LOG events (094). enums.md §15 catalogues AUDIT events — rows written to the
# audit log — and a structured log line's `event=` name is a different thing that would be a
# lie in that table. observability.md is their authority, the way it already is for metric
# names in check B, and §3.4A is the section that names them. Extracted from that section
# only, so a typo anywhere else still fails: the name has to be DEFINED before it is cited.
obs_txt = texts.get("docs/observability.md", "")
sec34a = re.search(r"^#### 3\.4A\b.*?(?=^### )", obs_txt, re.M | re.S)
if sec34a:
    events |= set(re.findall(r"`((?:auth|datasource|mcp|endpoint|pipeline)\.[a-z0-9_]+)`", sec34a.group(0)))
# lines stating a removal/rename may cite old spellings
NEGATION = re.compile(r"removed|renamed|deleted|replaced|superseded|folded|"
                      r"does not exist|no longer|instead of|there is no|no `|"
                      r"the old|used to|before 0\d\d|pre-0\d\d", re.I)

def code_ok(c):
    c = c.rstrip("*").rstrip(".")
    if c in catalog or c in events:
        return True
    return any(k.startswith(c + ".") for k in catalog | events)

# Non-error-code dotted names sharing a domain word: config namespaces,
# JSON field paths, and filenames.
CONFIG_PREFIXES = ("auth.oidc", "auth.jwt", "auth.allowlist", "auth.api-keys",
                   "auth.rate-limit", "result.ttl", "result.max", "result.page",
                   "idempotency.ttl", "template.cache", "pipeline.settings")
for p, t in texts.items():
    if p in EXEMPT_HISTORY or p in EXEMPT_PROPOSED:
        continue
    for i, line in body_lines(t):
        if NEGATION.search(line):
            continue
        for c in set(re.findall(CODE_RE, line)):
            if c.startswith(CONFIG_PREFIXES) or c.endswith(".js"):
                continue
            if (c + "(") in line:   # Kotlin method call, e.g. pipeline.copy(...)
                continue
            if not code_ok(c):
                failures.append(f"C {p}:{i}: error code not in pipeline-contract catalog: {c}")

# ---- D. forbidden legacy spellings -----------------------------------------
FORBIDDEN = [
    (r"DATAPIPLEINES", "typo'd env prefix"),
    (r"X-API-Key", "renamed to DP-API-Key (D10)"),
    (r"X-Correlation-Id", "renamed to DP-Correlation-Id (D10)"),
    (r"X-CSRF-Token", "renamed to DP-CSRF-Token (D10)"),
    (r"terminal_node_id", "field removed (D1)"),
    (r"dql_sink_missing_caller_target", "rule deleted (D1)"),
    (r"multiple_caller_targets", "renamed multiple_caller_nodes (D1)"),
    (r"h2_creation_failed", "renamed creation_failed (D5)"),
    (r"template\.import\.cycle_detected", "renamed template.validation.import_cycle (D5)"),
    (r"auth\.rate_limit\.exceeded", "removed in favor of rate_limit.exceeded (D5)"),
    (r"auth\.api_key_(missing|invalid|expired)", "renamed to auth.api_key.* (D5)"),
    (r"auth\.scope_insufficient", "renamed auth.scope.insufficient (D5)"),
    (r"idempotency_key\.", "renamed idempotency.* (D5)"),
    (r"result\.claim_check_expired", "renamed result.expired (D9)"),
    (r"delivery_mode", "field removed (D9)"),
    (r"LARGE_RESULT_THRESHOLD", "key removed (D9)"),
    (r"\"params_schema\"|params_schema\s+(JSONB|TEXT)", "removed (D3)"),
    (r"jdbc:h2:mem[^\s`\"']*DB_CLOSE_DELAY", "flag removed from staging URL (D6)"),
    # --- the deployment layout, 075 then 081 -------------------------------------
    # 075 renamed the compose files and replaced the dotenv scaffolds; 081 collapsed
    # the five tracked env files into deploy/env/defaults.env. Every one of these is
    # a path someone types from memory, and a doc that names one sends a deployer to
    # a file that is not there. `deploy/env/defaults.env` and
    # `deploy/secrets.env.example` are the only two spellings that exist.
    (r"docker-compose[.-]", "renamed compose.yml / compose.local-build.yml / compose.laptop-infra.yml (075)"),
    (r"deploy/\.env", "replaced by deploy/env/defaults.env + deploy/secrets.env (075/081)"),
    (r"\.env\.demo", "demo is a flag; the pins are in deploy/env/defaults.env (075/081)"),
    (r"\.env\.local", "replaced by deploy/env/defaults.env (075)"),
    (r"(?<!secrets)\.env\.example", "the template is deploy/secrets.env.example (081)"),
    (r"deploy/env/(laptop|demo|example)\.env", "collapsed into deploy/env/defaults.env (081)"),
    (r"deploy/env/posture/", "the posture's defaults live only in the profile ymls (081)"),
    (r"deploy/env/secrets\.env\.example", "moved to deploy/secrets.env.example (081)"),
    # --- secrets under compose-only names, 075 ------------------------------------
    # deploy/secrets.env carries the app's OWN variable names so one file feeds every
    # loader; a compose-only rename is what made it un-sourceable by a bare jar.
    (r"METADATA_DB_PASSWORD", "renamed SPRING_DATASOURCE_PASSWORD (075)"),
    (r"(?<![A-Z_])REDIS_PASSWORD=", "renamed DATAPIPELINES_REDIS_PASSWORD (075)"),
    (r"(?<![A-Z_])JWT_SECRET=", "renamed DATAPIPELINES_JWT_SECRET (075)"),
    (r"(?<![A-Z_])ENCRYPTION_KEY=", "renamed DATAPIPELINES_DB_ENCRYPTION_KEY (075)"),
]

# Check D alone also covers the files a deployer copies from. They are not docs, so
# checks A-C (anchors, config keys, error codes) do not apply to them.
LEGACY_SCAN_EXTRA = sorted(
    set(glob.glob("deploy/*.yml")) | set(glob.glob("deploy/*.example"))
    | set(glob.glob("deploy/env/*.env")) | set(glob.glob("deploy/sample-data/*.sh"))
    | set(glob.glob("scripts/*.sh")) | set(glob.glob("scripts/*/*.sh"))
    | set(glob.glob("scripts/*/README.md"))
    | {"README.md", "app.sh"}
)
for extra in LEGACY_SCAN_EXTRA:
    if os.path.isfile(extra) and extra not in texts:
        texts[extra] = read(extra)
for p, t in texts.items():
    if p in EXEMPT_HISTORY:
        continue
    in_changelog = False
    for i, line in enumerate(t.splitlines(), 1):
        if re.match(r"^#{1,6}\s", line):
            in_changelog = bool(re.search(r"change\s*log", line, re.I))
        if in_changelog or NEGATION.search(line):
            continue
        for pat, why in FORBIDDEN:
            if re.search(pat, line):
                failures.append(f"D {p}:{i}: forbidden '{pat}' — {why}")

# ---- report -----------------------------------------------------------------
for d in sorted(EXEMPT_PROPOSED):
    print(f"docs-audit: NOTICE {d} is not-yet-normative — exempt from check C "
          f"(proposed error codes must join docs/pipeline-contract.md \u00a713 when it lands)")
if failures:
    print(f"docs-audit: {len(failures)} failure(s)")
    for f in failures:
        print("  " + f)
    sys.exit(1)
print(f"docs-audit: OK ({len(DOCS)} files, {sum(len(s) for s in slugs.values())} headings indexed)")
PY
