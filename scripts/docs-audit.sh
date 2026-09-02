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
#      names from enums.md §15 are exempt (events, not error codes).
#   D. Forbidden legacy spellings (renamed/removed in the 2026-08 campaign)
#      outside Change Log sections.
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
  cp -R docs DEVELOPMENT.md scripts "$tmp/"
  # Introduce one defect per check class:
  {
    echo '[bad link](pipeline-contract.md#no-such-section)'
    echo 'Uses `datapipelines.no.such-key` here.'
    echo 'Raises `pipeline.validation.nonexistent_code` here.'
    echo 'Legacy `terminal_node_id` mention.'
  } >> "$tmp/docs/staging.md"
  if (cd "$tmp" && bash scripts/docs-audit.sh >/dev/null 2>&1); then
    echo "SELF-TEST FAILED: doctored docs passed the audit" >&2; exit 1
  else
    echo "self-test OK: doctored docs correctly fail the audit"; exit 0
  fi
fi

python3 - <<'PY'
import re, sys, glob, os

DOCS = sorted(glob.glob("docs/*.md")) + ["DEVELOPMENT.md"]
EXEMPT_HISTORY = {"docs/SPEC-REVIEW-2026-08.md"}
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
CODE_RE = (r"(?<![.\w-])(?:pipeline|template|datasource|auth|result|rate_limit|"
           r"idempotency|type_mapping)\.[a-z0-9_]+(?:\.[a-z0-9_*]+)*(?![\w-])")
catalog = set(re.findall(CODE_RE, texts["docs/pipeline-contract.md"]))
# datasource.validation.* is delegated: pipeline-contract §13.8 names Datasources §9
# as the defining list, so codes defined there join the catalog.
catalog |= {c for c in re.findall(CODE_RE, texts.get("docs/datasources.md", ""))
            if c.startswith("datasource.validation.")}
# audit events (enums §15) are events, not error codes — extract only from §15
enums_txt = texts.get("docs/enums.md", "")
sec15 = re.search(r"^## 15\..*?(?=^## 16\.)", enums_txt, re.M | re.S)
events = set(re.findall(r"(?:auth|datasource)\.[a-z_]+(?:\.[a-z_]+)*",
                        sec15.group(0) if sec15 else enums_txt))
# auth.* events are also cited outside §15 (auth.md §10.1 etc.)
events |= set(re.findall(r"auth\.[a-z_]+(?:\.[a-z_]+)*", enums_txt))
# lines stating a removal/rename may cite old spellings
NEGATION = re.compile(r"removed|renamed|deleted|replaced|superseded|folded|"
                      r"does not exist|no longer|instead of|there is no|no `", re.I)

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
]
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
