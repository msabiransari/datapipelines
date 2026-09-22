#!/usr/bin/env bash
# compose-argv-secrets-audit.sh — no secret VALUE is interpolated into a container's argv.
#
# The rule (deployment.md §4.2.1 and §9, #189/#196): a credential inside a container lives
# in the service's ENVIRONMENT. A `command:`, `entrypoint:` or `healthcheck.test:` may
# name the variable for a shell to expand at runtime (`$$REDISCLI_AUTH` — Compose's `$$`
# is a literal `$`, so the argv carries the NAME), but must never carry `${VAR}` or `$VAR`
# for a secret: Compose expands that into the argv string, which `docker inspect
# {{.Config.Cmd}}` prints and `ps` shows to every process in the container.
#
# Why a text check rather than `docker compose config`: the rendering needs the docker CLI
# and a daemon (the sibling audit's reason too), and the property IS textual — it is about
# which mechanism expands the variable, which the source decides.
#
# What counts as a secret: a variable whose name contains PASSWORD, SECRET or TOKEN, or
# ends in _KEY (an API key, an encryption key) — the same family `secrets.env.example`
# holds. Names like SAMPLE_VERSION or LAPTOP_PG_PORT are not credentials and are allowed.
#
# Files: every deploy/compose*.yml. Self-test: `--self-test` doctors temp copies with the
# three shapes this refuses (the pre-#196 `--requirepass ${…}` on argv, a `-a ${…}` on a
# healthcheck, a `$VAR` without braces) and must exit 1 on each — a guard that cannot go
# red is not a guard (MISTAKES.md).
#
#   scripts/compose-argv-secrets-audit.sh              # exit 0 when every file is clean
#   scripts/compose-argv-secrets-audit.sh --self-test  # exit 0 when every doctored copy fails

set -euo pipefail
cd "$(dirname "$0")/.."

audit() { # audit <file>... → exit 1 naming every offending line
  python3 - "$@" <<'PY'
import re, sys

SECRET = re.compile(r"(PASSWORD|SECRET|TOKEN|_KEY$|_KEY_|_PWD|_PASS$|_PASS_|CREDENTIAL)")
# A run of `$` before `{NAME` / `NAME`: Compose pairs `$$` into a literal `$`, so an EVEN run
# leaves the name for the container's shell and an ODD run interpolates the value here —
# `$NAME`, `${NAME}`, `${NAME:-…}` and `$$${NAME}` (a literal `$` glued to the VALUE) all
# interpolate; `$$NAME` does not.
INTERP = re.compile(r"(?<!\$)(\$+)\{?([A-Za-z_][A-Za-z0-9_]*)")
ARGV_KEYS = ("command", "entrypoint")

def indent(line):
    return len(line) - len(line.lstrip(" "))

def blocks(lines):
    """Yield (service, key, [(lineno, text)]) for every argv-carrying block."""
    service, in_services = None, False
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            i += 1; continue
        ind = indent(line)
        if ind == 0:
            in_services = stripped == "services:"
            service = None
        elif in_services and ind == 2 and stripped.endswith(":"):
            service = stripped[:-1]
        elif in_services and service and ind == 4:
            key = stripped.split(":", 1)[0]
            if key in ARGV_KEYS or key == "healthcheck":
                start = i; i += 1
                while i < len(lines) and (not lines[i].strip() or lines[i].strip().startswith("#") or indent(lines[i]) > 4):
                    i += 1
                body = [(n + 1, lines[n]) for n in range(start, i)]
                if key == "healthcheck":
                    body = [(n, t) for n, t in body if t.strip().startswith("test:") or indent(t) > 6]
                    key = "healthcheck.test"
                yield service, key, body
                continue
        i += 1

failures = []
for path in sys.argv[1:]:
    lines = open(path).read().split("\n")
    for service, key, body in blocks(lines):
        for lineno, text in body:
            code = text.split("#", 1)[0]
            for m in INTERP.finditer(code):
                if len(m.group(1)) % 2 == 1 and SECRET.search(m.group(2)):
                    failures.append(f"{path}:{lineno}: service '{service}' {key} interpolates the secret {m.group(2)} into argv: {text.strip()}")

if failures:
    print("compose-argv-secrets-audit: FAILED — a credential's VALUE is on a container command line:", file=sys.stderr)
    for f in failures:
        print("  - " + f, file=sys.stderr)
    print("  Move it to the service's environment and reference it by NAME ($$VAR) for a shell, or feed it through stdin (deployment.md §4.2.1).", file=sys.stderr)
    sys.exit(1)
print(f"compose-argv-secrets-audit: OK ({len(sys.argv) - 1} compose files; no secret value interpolated into any command, entrypoint or healthcheck)")
PY
}

shopt -s nullglob
FILES=(deploy/compose*.yml)
[[ ${#FILES[@]} -gt 0 ]] || { echo "compose-argv-secrets-audit: no deploy/compose*.yml found" >&2; exit 1; }

if [[ "${1:-}" == "--self-test" ]]; then
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  mkdir -p "$tmp/deploy"
  # Each doctored copy carries ONE shape the audit exists to refuse. The anchors are the
  # shipped redis blocks; if they move, this self-test fails loudly rather than passing
  # vacuously (the `assert` below).
  python3 - "$tmp" <<'PY'
import sys, os
tmp = sys.argv[1]
src = open("deploy/compose.yml").read()
anchor = "        requirepass $$REDISCLI_AUTH\n"
assert src.count(anchor) == 1, "self-test anchor missing from deploy/compose.yml"
cases = {
    # 1. the pre-#196 shape: the value on the server's argv
    "argv": src.replace(anchor, "        requirepass ${DATAPIPELINES_REDIS_PASSWORD}\n"),
    # 2. the value on a healthcheck's argv (`-a` is what #189 removed)
    "healthcheck": src.replace('test: ["CMD", "redis-cli", "ping"]', 'test: ["CMD", "redis-cli", "-a", "${DATAPIPELINES_REDIS_PASSWORD}", "ping"]'),
    # 3. a brace-less `$VAR`, which Compose interpolates just the same
    "bare": src.replace(anchor, "        requirepass $DATAPIPELINES_REDIS_PASSWORD\n"),
    # 4. `$$${VAR}`: Compose renders a literal `$` followed by the VALUE — the shape a
    #    lookbehind on one `$` would wave through (the #199 review's false negative)
    "double_dollar": src.replace(anchor, "        requirepass $$${DATAPIPELINES_REDIS_PASSWORD}\n"),
}
for name, text in cases.items():
    assert text != src, f"self-test case {name} changed nothing"
    d = os.path.join(tmp, name, "deploy"); os.makedirs(d)
    open(os.path.join(d, "compose.yml"), "w").write(text)
PY
  for case in argv healthcheck bare double_dollar; do
    if (cd "$tmp/$case" && audit deploy/compose.yml >/dev/null 2>&1); then
      echo "SELF-TEST FAILED: doctored compose ($case) passed the audit" >&2; exit 1
    fi
  done
  echo "self-test OK: 4 doctored compose files correctly fail the audit"; exit 0
fi

audit "${FILES[@]}"
