#!/usr/bin/env bash
# compose-env-audit.sh — the compose env-contract guard (T32).
#
# The operator trap this kills: a key that works in dev (host env) was silently absent
# under compose until someone added a pass-through line — deploy/compose.yml
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
#   5. deploy/env/example.env — the reference a deployer copies whatever their loader
#      is — lists EVERY bound variable with the shipped default. A variable that exists
#      only in the compose file is a variable a Kubernetes or systemd deployer cannot
#      discover, which is the whole of 075's "the contract is environment variables".
#
# Values built by CONCATENATION (`${OTHER:+literal}${OTHER2:+,literal}`) are DERIVED,
# not pass-throughs: the demo's bootstrap lists are assembled from the family ON
# markers. They are recognised as that form and still need a DECLARED_OVERRIDES entry.
#
# Scope: DATAPIPELINES_* only — SPRING_*/SERVER_*/MANAGEMENT_*/GOOGLE_* are framework
# wiring (configuration.md §3.14) or provider credentials with their own compose lines.
#
# Born 2026-09-02 (051 §B); wired into `./gradlew build` by 075 (T119) — before that it
# ran only when someone remembered, and 074 shipped three keys with no pass-through.
# Self-test: scripts/compose-env-audit.sh --self-test doctors a temp copy and must exit 1.

set -euo pipefail
cd "$(dirname "$0")/.."

APP_YML="modules/app/src/main/resources/application.yml"
COMPOSE="deploy/compose.yml"
EXAMPLE_ENV="deploy/env/example.env"
if [[ "${1:-}" == "--self-test" ]]; then
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  mkdir -p "$tmp/modules/app/src/main/resources" "$tmp/deploy/env"
  cp "$APP_YML" "$tmp/$APP_YML"
  cp "$COMPOSE" "$tmp/$COMPOSE"
  cp "$EXAMPLE_ENV" "$tmp/$EXAMPLE_ENV"
  # One defect per check class: a removed pass-through line (1), a diverged default (2),
  # a foreign literal without an allowlist entry (3), a line the app never binds (4),
  # and a variable dropped from the deployer's reference file (5).
  python3 - "$tmp/$COMPOSE" "$tmp/$EXAMPLE_ENV" <<'PY'
import sys, re
p, example = sys.argv[1], sys.argv[2]
t = open(p).read()
t = t.replace("      DATAPIPELINES_UI_THEME: ${DATAPIPELINES_UI_THEME:-saas}\n", "")
t = t.replace("DATAPIPELINES_AUDIT_RETENTION_DAYS:-365", "DATAPIPELINES_AUDIT_RETENTION_DAYS:-7")
t += "      DATAPIPELINES_MADE_UP_KEY: hard-coded\n"
open(p, "w").write(t)
e = open(example).read()
e = re.sub(r"^DATAPIPELINES_ORG_TIMEZONE=.*\n", "", e, flags=re.M)
open(example, "w").write(e)
PY
  if (cd "$tmp" && bash "scripts/compose-env-audit.sh" >/dev/null 2>&1); then
    echo "SELF-TEST FAILED: doctored compose passed the audit" >&2; exit 1
  else
    echo "self-test OK: doctored compose correctly fails the audit"; exit 0
  fi
fi

python3 - "$APP_YML" "$COMPOSE" "$EXAMPLE_ENV" <<'PY'
import re, sys

app_yml, compose, example_env = sys.argv[1], sys.argv[2], sys.argv[3]

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
    # 075 removed the three RENAMED secrets that used to live here (JWT_SECRET,
    # ENCRYPTION_KEY, REDIS_PASSWORD in deploy/.env): deploy/secrets.env now carries
    # every secret under the SAME name the app binds, so the same file can be sourced
    # by a bare `java -jar`, a systemd EnvironmentFile or a Kubernetes Secret. A rename
    # that exists only inside compose is exactly what environments.md forbids.
    #
    # DERIVED values (`${OTHER:+literal}` concatenation): the demo's two bootstrap lists
    # are assembled by compose from the family ON markers, because a compose file cannot
    # split a comma list and the app's key IS a comma list (configuration.md §3.18).
    "DATAPIPELINES_BOOTSTRAP_DATASOURCES_FILE": "derived from SAMPLE_NYC_ON / SAMPLE_TRADE_ON",
    "DATAPIPELINES_BOOTSTRAP_EXAMPLES_FILE": "derived from SAMPLE_NYC_ON / SAMPLE_TRADE_ON",
}

# A value assembled ENTIRELY from `${OTHER:+literal}` fragments — the concatenation form.
# Recognised so check 3 reports it as DERIVED rather than as a stray literal, and so a
# fragment that accidentally references the key ITSELF is still caught.
DERIVED = re.compile(r'^"?(?:\$\{[A-Z0-9_]+:\+[^}]*\})+"?$')

failures = []
for name in sorted(bound):
    if name not in passed:
        failures.append(f"1 compose is missing the pass-through line for {name} "
                        f"(application.yml binds it) — add `      {name}: ${{{name}:-{bound[name]}}}`")
for name, value in sorted(passed.items()):
    m = re.match(r"^\$\{" + name + r"(?::-?([^}]*))?\}$", value)
    if m:
        # `$$` is Compose's escape for a literal `$` — the container receives one dollar.
        # Comparing the raw text would report a diverged default for a value that is, in the
        # only place it matters (the process environment), identical. 072: the org currency
        # symbol is the first default in the file that needed it.
        default = (m.group(1) or "").replace("$$", "$")
        if default != bound.get(name):
            failures.append(f"2 compose default for {name} is '{default}' but application.yml ships "
                            f"'{bound.get(name)}' — the compose default must mirror the app default")
    elif name in DECLARED_OVERRIDES:
        pass
    elif DERIVED.match(value.strip()):
        failures.append(f"3 compose DERIVES {name} by concatenation ('{value}') — that is a legal form, but it "
                        f"must be declared in compose-env-audit.sh's DECLARED_OVERRIDES with the reason")
    else:
        failures.append(f"3 compose value for {name} is '{value}', not the same-name pass-through — "
                        f"declare it in compose-env-audit.sh's DECLARED_OVERRIDES with a reason, or make it a pass-through")
    if name not in bound:
        failures.append(f"4 compose passes {name} but application.yml never binds it — a dead line")

for name in sorted(DECLARED_OVERRIDES):
    if name not in passed:
        failures.append(f"3 DECLARED_OVERRIDES names {name} but compose no longer passes it — stale allowlist entry")

# 5. The deployer's reference file (075). Compose is ONE loader; example.env is what a
# Kubernetes, systemd, ECS or bare-jar deployer copies, so a variable missing from it is
# a variable those deployments cannot discover at all.
declared_example = dict(re.findall(r"^(DATAPIPELINES_[A-Z0-9_]+)=(.*)$", open(example_env).read(), re.M))
for name in sorted(bound):
    if name not in declared_example:
        failures.append(f"5 {example_env} is missing {name} — it is the reference every non-compose "
                        f"deployer copies; add `{name}={bound[name]}` with a one-line comment")
    elif declared_example[name] != bound[name]:
        failures.append(f"5 {example_env} shows {name}={declared_example[name]!r} but application.yml ships "
                        f"{bound[name]!r} — the reference must show the SHIPPED default")
for name in sorted(set(declared_example) - set(bound)):
    failures.append(f"5 {example_env} lists {name}, which application.yml never binds — a dead line in the "
                    f"one file deployers copy from")

if failures:
    print(f"compose-env-audit: {len(failures)} failure(s)")
    for f in failures:
        print("  " + f)
    sys.exit(1)
print(f"compose-env-audit: OK ({len(bound)} app env vars, {len(passed)} compose pass-throughs, "
      f"{len(DECLARED_OVERRIDES)} declared overrides, {len(declared_example)} lines in {example_env})")
PY
