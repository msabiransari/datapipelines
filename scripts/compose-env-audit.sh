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
#   5. ONE AUTHORITY over the two tracked env files (081). Every bound variable is
#      declared ACTIVELY in exactly one of deploy/env/defaults.env (non-secret defaults)
#      and deploy/secrets.env.example (secrets + this deployment's own values) — never
#      both, never neither — except the POSTURE-dependent pair, which is declared in
#      NEITHER because its default is the posture and lives in the profile ymls. A
#      defaults.env value must be application.yml's shipped default unless it is in
#      DEFAULTS_ENV_OVERRIDES with a reason.
#   6. THE NAME LIST. deploy/secrets.env.example additionally names, commented out, every
#      variable defaults.env declares, with defaults.env's value — the "all the env
#      names" file. A commented value that has drifted from defaults.env fails, so the
#      convenience copy cannot become a second authority.
#   7. THE POSTURE FORM. The two posture-dependent keys are passed by compose in the
#      valueless mapping form (`DATAPIPELINES_AUTH_COOKIE_SECURE:`), the only form that
#      leaves the variable UNSET when no file sets it. `${VAR:-}` and `${VAR:+…}` both
#      yield an empty string, which outranks the profile — 075's live defect.
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
DEFAULTS_ENV="deploy/env/defaults.env"
SECRETS_EXAMPLE="deploy/secrets.env.example"
if [[ "${1:-}" == "--self-test" ]]; then
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  mkdir -p "$tmp/modules/app/src/main/resources" "$tmp/deploy/env"
  cp "$APP_YML" "$tmp/$APP_YML"
  cp "$COMPOSE" "$tmp/$COMPOSE"
  cp "$DEFAULTS_ENV" "$tmp/$DEFAULTS_ENV"
  cp "$SECRETS_EXAMPLE" "$tmp/$SECRETS_EXAMPLE"
  # One defect per check class: a removed pass-through line (1), a diverged default (2),
  # a foreign literal without an allowlist entry (3), a line the app never binds (4), a
  # variable dropped from defaults.env (5), a drifted commented mirror (6), and a posture
  # key given a `${VAR:-}` default instead of the valueless form (7).
  python3 - "$tmp/$COMPOSE" "$tmp/$DEFAULTS_ENV" "$tmp/$SECRETS_EXAMPLE" <<'PY'
import sys, re
p, defaults, secrets_example = sys.argv[1], sys.argv[2], sys.argv[3]
t = open(p).read()
t = t.replace("DATAPIPELINES_AUDIT_RETENTION_DAYS:-365", "DATAPIPELINES_AUDIT_RETENTION_DAYS:-7")
t = t.replace("      DATAPIPELINES_ORG_WEEK_START: ${DATAPIPELINES_ORG_WEEK_START:-monday}\n", "")
t = t.replace("      DATAPIPELINES_AUTH_COOKIE_SECURE:\n",
              "      DATAPIPELINES_AUTH_COOKIE_SECURE: ${DATAPIPELINES_AUTH_COOKIE_SECURE:-}\n")
# INSIDE the service's environment block. Appending it at EOF put it outside the block
# the audit parses, so checks 3 and 4 never fired and the self-test was green on 1/2/5
# alone (found 2026-09-06 by printing the doctored run's failures instead of its exit code
# — MISTAKES.md: a guard that cannot go red is not a guard).
t = t.replace("      DATAPIPELINES_UI_THEME: ${DATAPIPELINES_UI_THEME:-saas}\n",
              "      DATAPIPELINES_MADE_UP_KEY: hard-coded\n")
open(p, "w").write(t)
e = open(defaults).read()
e = re.sub(r"^DATAPIPELINES_ORG_TIMEZONE=.*\n", "", e, flags=re.M)
open(defaults, "w").write(e)
s = open(secrets_example).read()
s = s.replace("# DATAPIPELINES_UI_THEME=saas", "# DATAPIPELINES_UI_THEME=drifted")
open(secrets_example, "w").write(s)
PY
  if (cd "$tmp" && bash "scripts/compose-env-audit.sh" >/dev/null 2>&1); then
    echo "SELF-TEST FAILED: doctored compose passed the audit" >&2; exit 1
  else
    echo "self-test OK: doctored compose correctly fails the audit"; exit 0
  fi
fi

python3 - "$APP_YML" "$COMPOSE" "$DEFAULTS_ENV" "$SECRETS_EXAMPLE" <<'PY'
import re, sys

app_yml, compose, defaults_env, secrets_example = sys.argv[1:5]

# The app's env contract: every DATAPIPELINES_* placeholder application.yml binds,
# with its shipped default ('' when the placeholder carries none or an empty one).
bound = {}
for name, default in re.findall(r"\$\{(DATAPIPELINES_[A-Z0-9_]+)(?::([^}]*))?\}", open(app_yml).read()):
    # A NESTED fallback chain (`${VAR:${other.property:}}`) is not a declaration of VAR's
    # default — it is one reader falling back to another property. 075's
    # `spring.profiles.active` is the only such line: the variable's real default is declared
    # where the app BINDS it (`datapipelines.posture`), further down this same file. Taking
    # the chain's text as a default would report every compose line as diverged.
    if default and default.startswith("${"):
        continue
    bound.setdefault(name, default or "")

# The compose `datapipelines` service environment.
t = open(compose).read()
svc = re.search(r"^  datapipelines:\n.*?^    environment:\n(.*?)(?=^    [a-z_]+:)", t, re.M | re.S)
if not svc:
    sys.exit(f"compose-env-audit: could not locate the datapipelines service environment block in {compose}")
passed = dict(re.findall(r"^      (DATAPIPELINES_[A-Z0-9_]+): (.*)$", svc.group(1), re.M))
# The VALUELESS mapping form — `DATAPIPELINES_AUTH_COOKIE_SECURE:` with nothing after the
# colon. Compose leaves the variable UNSET when no env file supplies it, which is the only
# way a profile default survives a compose boot (081 §B). It is not a pass-through and has
# no default to compare, so it is collected separately.
unset_unless_set = set(re.findall(r"^      (DATAPIPELINES_[A-Z0-9_]+):\s*$", svc.group(1), re.M))

# Values that deliberately differ from a same-name pass-through, each with its reason.
# Every entry must stay in use — an unused entry is stale and fails the audit.
DECLARED_OVERRIDES = {
    # The Redis host on the compose network is the SERVICE name, not application.yml's
    # loopback default (adopted 2026-09-02, 051 §B — the whole point of the compose stack).
    "DATAPIPELINES_REDIS_HOST": "compose-internal service name 'redis'",
    # Both halves of the Redis address are the compose network's, not the env file's:
    # deploy/env/defaults.env carries the laptop's PUBLISHED port (6381), and passing it
    # through would point the container at a port nothing listens on inside the network.
    "DATAPIPELINES_REDIS_PORT": "compose-internal port 6379, not the laptop's published 6381",
    # 075 removed the three RENAMED secrets that used to live here — the old JWT_SECRET,
    # ENCRYPTION_KEY and REDIS_PASSWORD spellings: deploy/secrets.env now carries
    # every secret under the SAME name the app binds, so the same file can be sourced
    # by a bare `java -jar`, a systemd EnvironmentFile or a Kubernetes Secret. A rename
    # that exists only inside compose is exactly what environments.md forbids.
    #
    # DERIVED values (`${OTHER:+literal}` concatenation): the demo's two bootstrap lists
    # are assembled by compose from the family ON markers, because a compose file cannot
    # split a comma list and the app's key IS a comma list (configuration.md §3.18).
    "DATAPIPELINES_BOOTSTRAP_DATASOURCES_FILE": "derived from SAMPLE_NYC_ON / SAMPLE_TRADE_ON / SAMPLE_LAKE_ON",
    "DATAPIPELINES_BOOTSTRAP_EXAMPLES_FILE": "derived from SAMPLE_NYC_ON / SAMPLE_TRADE_ON / SAMPLE_LAKE_ON",
}

# Vars whose compose default deliberately mirrors the DOCKERFILE's ENV, not application.yml
# (089 §D). A bare `java -jar` — which deploy/env/defaults.env also feeds — has no bundled
# DuckDB extension directory, so the app default is empty; the shipped image pre-populates
# the path below, and compose's `${VAR:-<path>}` fills it in only when the variable is unset
# or empty, so an explicit operator value in deploy/secrets.env still wins. defaults.env
# keeps the empty app default and check 5 compares THAT against application.yml as usual.
IMAGE_DEFAULT_VARS = {
    "DATAPIPELINES_DUCKDB_EXTENSION_DIRECTORY": "/opt/duckdb/extensions",
}

# A value assembled ENTIRELY from `${OTHER:+literal}` fragments — the concatenation form.
# Recognised so check 3 reports it as DERIVED rather than as a stray literal, and so a
# fragment that accidentally references the key ITSELF is still caught.
DERIVED = re.compile(r'^"?(?:\$\{[A-Z0-9_]+:\+[^}]*\})+"?$')

# THE POSTURE-DEPENDENT KEYS (081 §B). Their default is the posture and lives ONLY in
# application-development.yml / application-hardened.yml; no env file may name them, and
# compose must pass them in the valueless form. PostureDefaultsSpecDriftTest pins the yml
# half of the same rule and fails if this list and the ymls disagree.
POSTURE_VARS = {
    "DATAPIPELINES_DEPLOYMENT_AUTHORING_ENABLED",
    "DATAPIPELINES_AUTH_COOKIE_SECURE",
}

# SECRETS: credentials, and refused in a tracked file under any circumstance.
SECRET_VARS = {
    "DATAPIPELINES_JWT_SECRET",
    "DATAPIPELINES_DB_ENCRYPTION_KEY",
    "DATAPIPELINES_REDIS_PASSWORD",
    "DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD",
    "DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD_HASH",
    "DATAPIPELINES_DEPLOYMENT_PROMOTION_SERVER_KEY",
    "DATAPIPELINES_DEPLOYMENT_PROMOTION_TARGET_KEY",
}
# THIS DEPLOYMENT'S OWN VALUES: not secret, but the product cannot pick them, so they
# have no tracked default either. They sit beside the secrets in secrets.env.example.
IDENTITY_VARS = {
    "DATAPIPELINES_AUTH_BASE_URL",
    "DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL",
    "DATAPIPELINES_DEPLOYMENT_PROMOTION_TARGET_URL",
}

# defaults.env values that deliberately differ from application.yml's shipped default,
# each with its reason. Anything not listed here must match the app default exactly.
DEFAULTS_ENV_OVERRIDES = {
    "DATAPIPELINES_POSTURE":
        "the shipped deployment is a laptop; any other environment sets it in secrets.env",
    "DATAPIPELINES_REDIS_HOST":
        "compose.laptop-infra.yml publishes Redis on the host, not on a loopback default",
    "DATAPIPELINES_REDIS_PORT":
        "compose.laptop-infra.yml publishes 6381 (6379 collides with other local stacks)",
    "DATAPIPELINES_OBSERVABILITY_LOGGING_FORMAT":
        "console is readable on a terminal; a log collector sets json in secrets.env",
    "DATAPIPELINES_WORKSPACES_MEMBER_DATASOURCES_ENABLED":
        "an open datasource form on a reachable server is an SSRF primitive (sample-data §7)",
}

def active(path):
    """KEY=value assignments that are NOT commented out, in file order."""
    return re.findall(r"^([A-Z][A-Z0-9_]*)=(.*)$", open(path).read(), re.M)

def commented(path):
    """`# KEY=value` lines — the name list, not a declaration."""
    return re.findall(r"^#\s*([A-Z][A-Z0-9_]*)=(.*)$", open(path).read(), re.M)

failures = []
for name in sorted(bound):
    if name in POSTURE_VARS:
        if name not in unset_unless_set:
            failures.append(f"7 compose must pass {name} in the VALUELESS form "
                            f"(`      {name}:`) — it is posture-dependent, and any "
                            f"`${{{name}:-…}}` sets the variable to empty, which outranks "
                            f"the profile that carries the posture's default")
        continue
    if name in unset_unless_set:
        failures.append(f"7 compose passes {name} in the valueless form, but it is not "
                        f"posture-dependent — give it the `${{{name}:-{bound[name]}}}` "
                        f"pass-through so the shipped default reaches the container")
    if name not in passed:
        failures.append(f"1 compose is missing the pass-through line for {name} "
                        f"(application.yml binds it) — add `      {name}: ${{{name}:-{bound[name]}}}`")
for name, value in sorted(passed.items()):
    if name in POSTURE_VARS:
        continue
    m = re.match(r"^\$\{" + name + r"(?::-?([^}]*))?\}$", value)
    if m:
        # `$$` is Compose's escape for a literal `$` — the container receives one dollar.
        # Comparing the raw text would report a diverged default for a value that is, in the
        # only place it matters (the process environment), identical. 072: the org currency
        # symbol is the first default in the file that needed it.
        default = (m.group(1) or "").replace("$$", "$")
        if name in IMAGE_DEFAULT_VARS:
            if default != IMAGE_DEFAULT_VARS[name]:
                failures.append(f"2 compose default for {name} is '{default}' but the image bundles "
                                f"'{IMAGE_DEFAULT_VARS[name]}' — mirror the Dockerfile ENV, or drop "
                                f"this script's IMAGE_DEFAULT_VARS entry for it")
        elif default != bound.get(name):
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

# 5/6. ONE AUTHORITY over the two tracked files (081). The owner's ruling was "only one
# env with defaults + secrets" — so every variable is declared ACTIVELY in exactly one of
# them, and the posture pair in neither. secrets.env.example additionally NAMES every
# other variable in a commented block (the "all the env names" file), and those commented
# values must equal defaults.env's or the convenience copy becomes a second authority.
defaults_active = dict(active(defaults_env))
secrets_active = dict(active(secrets_example))
secrets_named = dict(commented(secrets_example))

expected_secrets_side = SECRET_VARS | IDENTITY_VARS
for name in sorted(bound):
    in_defaults = name in defaults_active
    in_secrets = name in secrets_active
    if name in POSTURE_VARS:
        if in_defaults or in_secrets:
            where = defaults_env if in_defaults else secrets_example
            failures.append(f"5 {where} declares {name}, which is posture-dependent — its "
                            f"default belongs ONLY to application-development.yml / "
                            f"application-hardened.yml; comment it out or delete the line")
        elif name not in secrets_named:
            failures.append(f"5 neither tracked file NAMES {name} — add it to "
                            f"{secrets_example}'s commented list so a deployer can find it")
        continue
    if in_defaults and in_secrets:
        failures.append(f"5 {name} is declared in BOTH {defaults_env} and {secrets_example} "
                        f"— exactly one file owns each variable")
    elif name in expected_secrets_side:
        if not in_secrets:
            kind = "a secret" if name in SECRET_VARS else "this deployment's own value"
            failures.append(f"5 {secrets_example} is missing {name} — it is {kind} and has "
                            f"no tracked default; add `{name}=` with a one-line comment")
        elif secrets_active[name] != "":
            failures.append(f"5 {secrets_example} ships {name}={secrets_active[name]!r} — a "
                            f"value in this template is a published credential; leave it empty")
        if in_defaults:
            failures.append(f"5 {defaults_env} declares {name}, which belongs in "
                            f"{secrets_example} — a tracked file must never carry it")
    elif not in_defaults:
        failures.append(f"5 {defaults_env} is missing {name} — it is the file every loader "
                        f"reads first; add `{name}={bound[name]}` with a one-line comment")
    else:
        want = bound[name]
        got = defaults_active[name]
        if name in DEFAULTS_ENV_OVERRIDES:
            if got == want:
                failures.append(f"5 {defaults_env} sets {name}={got!r}, which is now the same "
                                f"as application.yml's default — drop the stale "
                                f"DEFAULTS_ENV_OVERRIDES entry in this script")
        elif got != want:
            failures.append(f"5 {defaults_env} shows {name}={got!r} but application.yml ships "
                            f"{want!r} — either fix the line or declare the difference in "
                            f"this script's DEFAULTS_ENV_OVERRIDES with a reason")

for name in sorted(set(defaults_active) | set(secrets_active)):
    if name.startswith("DATAPIPELINES_") and name not in bound:
        where = defaults_env if name in defaults_active else secrets_example
        failures.append(f"5 {where} declares {name}, which application.yml never binds — a "
                        f"dead line in a file deployers copy from")
for name in sorted(DEFAULTS_ENV_OVERRIDES):
    if name not in defaults_active:
        failures.append(f"5 DEFAULTS_ENV_OVERRIDES names {name}, which {defaults_env} no "
                        f"longer declares — stale allowlist entry")

# 6. The commented name list mirrors defaults.env exactly.
for name, value in sorted(defaults_active.items()):
    if name not in secrets_named:
        failures.append(f"6 {secrets_example} does not name {name} — its commented list is "
                        f"the 'every variable you may set' reference; add `# {name}={value}`")
    elif secrets_named[name] != value:
        failures.append(f"6 {secrets_example} shows `# {name}={secrets_named[name]}` but "
                        f"{defaults_env} sets {value!r} — the commented mirror has drifted")

# The sample-data pins and the two metadata-DB settings are not DATAPIPELINES_*
# variables, so nothing above reaches them; they are named here because
# scripts/sample-data/check-published.sh, scripts/sample-data-lake/check-published.sh
# (the lake pair, 089 §E) and both compose files read them out of defaults.env by name.
for name in ("SAMPLE_BASE_URL", "SAMPLE_VERSION", "SAMPLE_TRADE_BASE_URL",
             "SAMPLE_TRADE_VERSION", "SAMPLE_LAKE_BASE_URL", "SAMPLE_LAKE_VERSION",
             "SAMPLE_DB_USER",
             "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME"):
    if name not in defaults_active:
        failures.append(f"5 {defaults_env} is missing {name} — check-published.sh and the "
                        f"compose files read it from this file by name")

if failures:
    print(f"compose-env-audit: {len(failures)} failure(s)")
    for f in failures:
        print("  " + f)
    sys.exit(1)
print(f"compose-env-audit: OK ({len(bound)} app env vars; {len(passed)} compose pass-throughs "
      f"+ {len(unset_unless_set)} posture keys passed only when set; {len(DECLARED_OVERRIDES)} "
      f"declared compose overrides; one authority each — {len(defaults_active)} active in "
      f"{defaults_env} ({len(DEFAULTS_ENV_OVERRIDES)} declared to differ from application.yml), "
      f"{len(secrets_active)} in {secrets_example}, {len(POSTURE_VARS)} owned by the posture "
      f"profiles; {len(secrets_named)} names in the commented reference list)")
PY
