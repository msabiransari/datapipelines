#!/usr/bin/env bash
# compose-env-audit.sh — the compose env-contract guard (T32).
#
# The operator trap this kills: a key that works in dev (host env) was silently absent
# under compose until someone added a pass-through line — deploy/docker-compose.yml
# passed only a hand-picked subset of DATAPIPELINES_* variables. The class, not the
# instance: compose now passes EVERY such variable the app binds, each with the same
# default application.yml ships, and this script fails when either side drifts.
#
# Checks (exit 1 on any failure):
#   1. Every DATAPIPELINES_* placeholder in application.yml has a pass-through line in
#      the compose `datapipelines` service environment.
#   2. A same-name pass-through `${VAR:-default}` carries EXACTLY the application.yml
#      default (a diverged default is a third authority — the file's own rule).
#   3. A value that is NOT a same-name placeholder (a literal, or sourced from another
#      variable like ${JWT_SECRET}) must be in the DECLARED_OVERRIDES allowlist below,
#      and every allowlist entry must still be in use (stale entries fail too).
#   4. No compose DATAPIPELINES_* line the app never binds.
#
# Scope: DATAPIPELINES_* only — SPRING_*/SERVER_*/MANAGEMENT_*/GOOGLE_* are framework
# wiring (configuration.md §3.14) or provider credentials with their own compose lines.
#
# Born 2026-09-02 (051 §B). Self-test: scripts/compose-env-audit.sh --self-test doctors
# a temp copy (removes one pass-through line) and must exit 1.

set -euo pipefail
cd "$(dirname "$0")/.."

APP_YML="modules/app/src/main/resources/application.yml"
COMPOSE="deploy/docker-compose.yml"
if [[ "${1:-}" == "--self-test" ]]; then
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  mkdir -p "$tmp/modules/app/src/main/resources" "$tmp/deploy"
  cp "$APP_YML" "$tmp/$APP_YML"
  cp "$COMPOSE" "$tmp/$COMPOSE"
  # One defect per check class: a removed pass-through line (1), a diverged default (2),
  # a foreign literal without an allowlist entry (3), and a line the app never binds (4).
  python3 - "$tmp/$COMPOSE" <<'PY'
import sys, re
p = sys.argv[1]
t = open(p).read()
t = t.replace("      DATAPIPELINES_UI_THEME: ${DATAPIPELINES_UI_THEME:-saas}\n", "")
t = t.replace("DATAPIPELINES_AUDIT_RETENTION_DAYS:-365", "DATAPIPELINES_AUDIT_RETENTION_DAYS:-7")
t += "      DATAPIPELINES_MADE_UP_KEY: hard-coded\n"
open(p, "w").write(t)
PY
  if (cd "$tmp" && bash "scripts/compose-env-audit.sh" >/dev/null 2>&1); then
    echo "SELF-TEST FAILED: doctored compose passed the audit" >&2; exit 1
  else
    echo "self-test OK: doctored compose correctly fails the audit"; exit 0
  fi
fi

python3 - "$APP_YML" "$COMPOSE" <<'PY'
import re, sys

app_yml, compose = sys.argv[1], sys.argv[2]

# The app's env contract: every DATAPIPELINES_* placeholder application.yml binds,
# with its shipped default ('' when the placeholder carries none or an empty one).
bound = {}
for name, default in re.findall(r"\$\{(DATAPIPELINES_[A-Z0-9_]+)(?::([^}]*))?\}", open(app_yml).read()):
    bound.setdefault(name, default or "")

# The compose `datapipelines` service environment.
t = open(compose).read()
svc = re.search(r"^  datapipelines:\n.*?^    environment:\n(.*?)(?=^    [a-z_]+:)", t, re.M | re.S)
if not svc:
    sys.exit(f"compose-env-audit: could not locate the datapipelines service environment block in {compose}")
passed = dict(re.findall(r"^      (DATAPIPELINES_[A-Z0-9_]+): (.*)$", svc.group(1), re.M))

# Values that deliberately differ from a same-name pass-through, each with its reason.
# Every entry must stay in use — an unused entry is stale and fails the audit.
DECLARED_OVERRIDES = {
    # The Redis host on the compose network is the SERVICE name, not application.yml's
    # loopback default (adopted 2026-09-02, 051 §B — the whole point of the compose stack).
    "DATAPIPELINES_REDIS_HOST": "compose-internal service name 'redis'",
    # Required secrets sourced from deploy/.env under their infra names — compose is the
    # layer that renames them; the app still binds the DATAPIPELINES_* name.
    "DATAPIPELINES_JWT_SECRET": "sourced from deploy/.env JWT_SECRET",
    "DATAPIPELINES_DB_ENCRYPTION_KEY": "sourced from deploy/.env ENCRYPTION_KEY",
    "DATAPIPELINES_REDIS_PASSWORD": "sourced from deploy/.env REDIS_PASSWORD",
}

failures = []
for name in sorted(bound):
    if name not in passed:
        failures.append(f"1 compose is missing the pass-through line for {name} "
                        f"(application.yml binds it) — add `      {name}: ${{{name}:-{bound[name]}}}`")
for name, value in sorted(passed.items()):
    m = re.match(r"^\$\{" + name + r"(?::-?([^}]*))?\}$", value)
    if m:
        default = m.group(1) or ""
        if default != bound.get(name):
            failures.append(f"2 compose default for {name} is '{default}' but application.yml ships "
                            f"'{bound.get(name)}' — the compose default must mirror the app default")
    elif name in DECLARED_OVERRIDES:
        pass
    else:
        failures.append(f"3 compose value for {name} is '{value}', not the same-name pass-through — "
                        f"declare it in compose-env-audit.sh's DECLARED_OVERRIDES with a reason, or make it a pass-through")
    if name not in bound:
        failures.append(f"4 compose passes {name} but application.yml never binds it — a dead line")

for name in sorted(DECLARED_OVERRIDES):
    if name not in passed:
        failures.append(f"3 DECLARED_OVERRIDES names {name} but compose no longer passes it — stale allowlist entry")

if failures:
    print(f"compose-env-audit: {len(failures)} failure(s)")
    for f in failures:
        print("  " + f)
    sys.exit(1)
print(f"compose-env-audit: OK ({len(bound)} app env vars, {len(passed)} compose pass-throughs, "
      f"{len(DECLARED_OVERRIDES)} declared overrides)")
PY
