#!/usr/bin/env bash
# helm-check.sh — render the reference Helm chart (deploy/helm/datapipelines)
# with a PINNED helm binary: `helm lint --strict` plus four `helm template`
# cases whose assertions pin the chart's render-TIME behaviour — the Kotlin
# drift test HelmDuckDbValuesSpecDriftTest pins only the chart's TEXT (the
# pregate has no helm binary), so the real engine is exercised here (#411,
# closing the text-vs-engine gap #140 left).
#
#   ./scripts/helm-check.sh        # from the repo root; see EXIT CONTRACT
#
# The cases (one PASS/FAIL line each, naming the assertion that decided it):
#   lint  `helm lint --strict` with the placeholder secret. If --strict is red
#         on the untouched chart, the output is logged, lint re-runs WITHOUT
#         --strict and its warnings are REPORTED — the chart is the thing
#         under test and is never edited here; the case fails only if even the
#         non-strict lint exits non-zero.
#   (a)   defaults: exit 0; NO DuckDB env entry, NO rendered `value: ""`, no
#         `env:` block at all (an empty entry would override the image's own
#         DATAPIPELINES_DUCKDB_EXTENSION_DIRECTORY — the #140 trap).
#   (b)   both duckdb values set: exit 0; each env name exactly once, with its
#         quoted value; no rendered `value: ""`.
#   (c)   the extraEnv double mapping, TWICE: each render exits non-zero AND
#         stderr carries the template's own `fail` message VERBATIM
#         (deployment.yaml:40 for the extension directory, :41 for the memory
#         limit) — never just a non-zero exit, which (d) shows fires for the
#         wrong reason too.
#   (d)   no secret name (the placeholder OMITTED): exit non-zero AND stderr
#         carries `existingSecret is required` — pins the `required` guard and
#         proves the placeholder is load-bearing for (a)–(c).
#
# Tool: helm, PINNED. CI-only by the owner's ruling (2026-10-02): the pregate
# and gate.sh have no helm stage (no binary on the dev box); this script runs
# as the gate job's "render the reference Helm chart" step in
# .github/workflows/ci.yml, before the build, and is runnable by hand on a
# networked machine.
#
# The pin follows vuln-scan.sh's #198 pattern: HELM_SUMS_SHA256 below is the
# SHA-256 of the pinned release's checksum file, computed from a fresh
# download. helm publishes ONE .sha256sum PER PLATFORM ASSET (not the one
# multi-platform SHA256SUMS manifest osv-scanner ships):
#   https://get.helm.sh/helm-<version>-<os>-<arch>.tar.gz
#   https://get.helm.sh/helm-<version>-<os>-<arch>.tar.gz.sha256sum  (one
#     line, sha256sum format, naming the tarball; the GitHub release page
#     carries detached .asc signatures)
# The downloaded manifest is checked against the in-repo pin BEFORE it is used
# to verify the tarball, and BOTH checks run on EVERY run — not only at
# install — so a release page (or cache) that swaps the tarball fails the
# manifest verify and one that also swaps the manifest fails the pin, either
# way exit 2. The EXECUTED binary is extracted fresh from the verified tarball
# into a mktemp -d on every run: the cache holds the tarball and its manifest
# per version, never a trusted-at-install binary, so a corrupted or swapped
# cached binary is impossible to execute. helm itself runs hermetic: its
# config/cache homes are pointed into the same mktemp -d. Refresh the pin WITH
# the version, in one commit (DEVELOPMENT.md §10.2, "Bumping helm").
#
# EXIT CONTRACT (never conflate a verdict with tooling):
#   0   every case passed
#   1   a chart check failed (a verdict — the chart is broken or drifted)
#   2   tooling: download failure, manifest-pin or tarball-hash mismatch (a
#       supply-chain failure, not a chart verdict), unsupported platform,
#       missing chart, any unhandled tooling error — no verdict
# There is deliberately NO offline skip: the gate job is online, and a local
# cold run without network must fail loudly rather than silently skip the
# render gate.

set -Eeuo pipefail
# -E/errtrace: the ERR trap is inherited by shell functions (014/F2) — any
# UNHANDLED tooling failure (mkdir, tar, mv, …) exits 2 with its line, never a
# raw set -e death whose status could collide with the verdict codes. Handled
# failures (|| , if, elif, set +e blocks) never fire it.
trap 'echo "helm-check: unexpected tooling failure at line $LINENO — no verdict" >&2; exit 2' ERR
cd "$(dirname "$0")/.."
ROOT="$PWD"
source "$ROOT/scripts/lib/scan-tools.sh"

CHART_DIR="$ROOT/deploy/helm/datapipelines"
# deployment.yaml: `required "existingSecret is required …"` — every case that
# renders successfully passes this obviously fake NAME; the chart's secretRef
# names a Secret an operator creates, it never carries a value.
PLACEHOLDER="ci-placeholder-secret"

HELM_VERSION="v4.3.0"   # verified latest release (github.com/helm/helm, the "Latest" label), 2026-10-03
# #411 — the SHA-256 of helm-v4.3.0-linux-amd64.tar.gz.sha256sum (the pinned
# release's checksum file for this platform), computed 2026-10-03 from a fresh
# download of
#   https://get.helm.sh/helm-v4.3.0-linux-amd64.tar.gz.sha256sum
# (cross-checked: the release's flat .sha256 file names the same tarball hash
# as the .sha256sum line). Refresh it WITH the version, in one commit
# (DEVELOPMENT.md §10.2, "Bumping helm").
HELM_SUMS_SHA256="b44f0bf701af457342007f037ca22d79f4d25be431b258e2126d0b237f9da310"

TOOL_DIR="$(scan_tools_dir helm)"
os=$(uname -s | tr '[:upper:]' '[:lower:]')    # darwin | linux
# trap - ERR inside the substitution (018/F3, as in vuln-scan.sh): errtrace
# makes the command-substitution subshell inherit this script's ERR trap, so
# scan_tools_arch's HANDLED `return 1` (unsupported arch) would fire it and
# print the spurious "unexpected tooling failure" line. Removing it in the
# subshell silences the line; the parent's trap is untouched; EXIT stays 2.
arch=$(trap - ERR; scan_tools_arch amd64) || { echo "helm-check: unsupported architecture $(uname -m)" >&2; exit 2; }
ASSET="helm-${HELM_VERSION}-${os}-${arch}.tar.gz"
TARBALL="$TOOL_DIR/$ASSET"
# The manifest is cached beside the tarball it verifies (one per version+platform).
SUMS="$TARBALL.sha256sum"
RELEASE_BASE="https://get.helm.sh"

# The manifest's own integrity: the cached or freshly downloaded .sha256sum
# must hash to the in-repo pin BEFORE it is used to verify the tarball (#198).
# A mismatch exits 2 printing both hashes and deletes the manifest so the next
# online run re-downloads it rather than caching a bad one.
verify_manifest_pin() {
  local got
  got=$(shasum -a 256 "$SUMS" 2>/dev/null | awk '{print $1}') || true
  if [ "$got" != "$HELM_SUMS_SHA256" ]; then
    echo "helm-check: FAIL — $ASSET.sha256sum does not match the in-repo pin (pin=$HELM_SUMS_SHA256 got=$got); a swapped manifest is a supply-chain failure, not a chart verdict." >&2
    rm -f "$SUMS"
    exit 2
  fi
}

install_helm() {
  mkdir -p "$TOOL_DIR"
  echo "helm-check: installing helm ${HELM_VERSION} (${ASSET}) into $TOOL_DIR"
  echo "helm-check:   tarball:  $RELEASE_BASE/$ASSET"
  echo "helm-check:   manifest: $RELEASE_BASE/$ASSET.sha256sum"
  scan_tools_download helm-check "$RELEASE_BASE/$ASSET" "$TARBALL"
  scan_tools_download helm-check "$RELEASE_BASE/$ASSET.sha256sum" "$SUMS"
  # The manifest is checked against the in-repo pin BEFORE it verifies the
  # tarball (#198): a release page that swaps BOTH cannot pass.
  verify_manifest_pin
  # Verified BEFORE the prune: a bad download must never cost the older
  # tarball the prune would otherwise leave as the fallback.
  scan_tools_verify_sha256 helm-check "$TARBALL" "$SUMS" "$ASSET"
  scan_tools_prune helm-check helm "$TARBALL"
}

# Cached = tarball AND its manifest; a tarball without the manifest beside it
# cannot be re-verified, so it is reinstalled rather than trusted.
helm_cached() {
  [ -f "$TARBALL" ] && [ -f "$SUMS" ]
}

[ -d "$CHART_DIR" ] || { echo "helm-check: FAIL — $CHART_DIR is missing; nothing to render (tooling, not a verdict)" >&2; exit 2; }

helm_cached || install_helm
# Every run, not only the install (#193): the cache is machine-level and
# long-lived, so the tarball is checked against the manifest cached beside it,
# after the manifest is checked against the in-repo pin (#198), before
# anything is extracted. A mismatch prints both hashes and exits 2.
verify_manifest_pin
scan_tools_verify_sha256 helm-check "$TARBALL" "$SUMS" "$ASSET"
echo "helm-check: helm ${HELM_VERSION} verified (manifest pin + tarball SHA-256)"

WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT
tar -xzf "$TARBALL" -C "$WORK"
HELM="$WORK/${os}-${arch}/helm"
[ -x "$HELM" ] || { echo "helm-check: FAIL — $ASSET did not contain ${os}-${arch}/helm (helm's packaging layout changed?)" >&2; exit 2; }
# Hermetic run: no ~/.config/helm or ~/.cache/helm is read or written — the
# only writes land under $WORK (removed on exit) and the tools cache.
export HELM_CONFIG_HOME="$WORK/helm-config"
export HELM_CACHE_HOME="$WORK/helm-cache"

fails=0
verdict() {  # verdict <case-label> <ok:0|1> <the assertion that decided it>
  if [ "$2" -eq 0 ]; then
    echo "PASS: $1 — $3"
  else
    echo "FAIL: $1 — $3" >&2
    fails=$((fails + 1))
  fi
}

# lint — strict first; a strict red on the untouched chart falls back to
# non-strict and REPORTS the warnings (the chart is not edited here). Expected
# non-zero exits are captured `|| rc=$?` — the || list, not set +e, is what
# keeps the ERR trap from firing on a verdict-bearing non-zero (014/F2).
lint_rc=0
"$HELM" lint --strict --set "existingSecret=$PLACEHOLDER" "$CHART_DIR" >"$WORK/lint.out" 2>"$WORK/lint.err" || lint_rc=$?
if [ "$lint_rc" -eq 0 ]; then
  verdict "lint --strict" 0 "helm lint --strict exited 0"
else
  echo "helm-check: --strict lint exited $lint_rc; re-running WITHOUT --strict and reporting its findings (chart not edited):" >&2
  cat "$WORK/lint.out" "$WORK/lint.err" >&2
  lint_ns_rc=0
  "$HELM" lint --set "existingSecret=$PLACEHOLDER" "$CHART_DIR" >"$WORK/lint-nonstrict.out" 2>"$WORK/lint-nonstrict.err" || lint_ns_rc=$?
  echo "helm-check: --- non-strict lint output (warnings REPORTED, not silenced):" >&2
  cat "$WORK/lint-nonstrict.out" "$WORK/lint-nonstrict.err" >&2
  verdict "lint (non-strict fallback)" "$lint_ns_rc" "helm lint exited $lint_ns_rc (strict was $lint_rc)"
fi

# (a) defaults — no DuckDB env, no rendered "", no env: block.
rc=0
"$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" >"$WORK/a.out" 2>"$WORK/a.err" || rc=$?
if [ "$rc" -ne 0 ]; then
  verdict "(a) defaults" 1 "helm template exited $rc (expected 0)"
else
  n_dbu=$(grep -c 'DATAPIPELINES_DUCKDB_' "$WORK/a.out" || true)
  n_emp=$(grep -c 'value: ""' "$WORK/a.out" || true)
  n_env=$(grep -c '^ *env:' "$WORK/a.out" || true)
  if [ "$n_dbu" -ne 0 ]; then
    verdict "(a) defaults" 1 "grep -c 'DATAPIPELINES_DUCKDB_' = $n_dbu (expected 0 — an empty entry would override the image's ENV)"
  elif [ "$n_emp" -ne 0 ]; then
    verdict "(a) defaults" 1 "grep -c 'value: \"\"' = $n_emp (expected 0 — an empty env value still SETS the variable)"
  elif [ "$n_env" -ne 0 ]; then
    verdict "(a) defaults" 1 "grep -c '^ *env:' = $n_env (expected 0 — the env: block renders only when a source is set)"
  else
    verdict "(a) defaults" 0 "exit 0; 0 DuckDB entries, 0 rendered empty values, no env: block"
  fi
fi

# (b) both duckdb values set — each env name exactly once, quoted value.
rc=0
"$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
  --set duckdb.extensionDirectory=/opt/ext --set duckdb.memoryLimit=8GB >"$WORK/b.out" 2>"$WORK/b.err" || rc=$?
if [ "$rc" -ne 0 ]; then
  verdict "(b) both set" 1 "helm template exited $rc (expected 0)"
else
  n_ext=$(grep -c 'name: DATAPIPELINES_DUCKDB_EXTENSION_DIRECTORY$' "$WORK/b.out" || true)
  n_mem=$(grep -c 'name: DATAPIPELINES_DUCKDB_MEMORY_LIMIT$' "$WORK/b.out" || true)
  n_vext=$(grep -cF 'value: "/opt/ext"' "$WORK/b.out" || true)
  n_vmem=$(grep -cF 'value: "8GB"' "$WORK/b.out" || true)
  n_emp=$(grep -c 'value: ""' "$WORK/b.out" || true)
  if [ "$n_ext" -ne 1 ]; then
    verdict "(b) both set" 1 "DATAPIPELINES_DUCKDB_EXTENSION_DIRECTORY named $n_ext time(s) (expected exactly 1)"
  elif [ "$n_mem" -ne 1 ]; then
    verdict "(b) both set" 1 "DATAPIPELINES_DUCKDB_MEMORY_LIMIT named $n_mem time(s) (expected exactly 1)"
  elif [ "$n_vext" -ne 1 ]; then
    verdict "(b) both set" 1 "value: \"/opt/ext\" found $n_vext time(s) (expected exactly 1, quoted)"
  elif [ "$n_vmem" -ne 1 ]; then
    verdict "(b) both set" 1 "value: \"8GB\" found $n_vmem time(s) (expected exactly 1, quoted)"
  elif [ "$n_emp" -ne 0 ]; then
    verdict "(b) both set" 1 "grep -c 'value: \"\"' = $n_emp (expected 0)"
  else
    verdict "(b) both set" 0 "exit 0; each env name exactly once with its quoted value"
  fi
fi

# (c) the extraEnv double mapping, TWICE — each render must refuse with the
# template's own `fail` message, verbatim (deployment.yaml:40 / :41).
check_double_mapping() {  # <label> <extraEnv key> <value> <verbatim fail message> <out> <err>
  local label="$1" key="$2" val="$3" msg="$4" out="$5" err="$6" rc=0
  "$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
    --set "extraEnv.$key=$val" >"$out" 2>"$err" || rc=$?
  if [ "$rc" -eq 0 ]; then
    verdict "$label" 1 "helm template exited 0 (expected the render-time refusal)"
  elif grep -qF "$msg" "$err"; then
    verdict "$label" 0 "exit $rc with the template's own refusal message"
  else
    verdict "$label" 1 "exit $rc but the exact refusal message is NOT on stderr (red for the wrong reason)"
  fi
}
check_double_mapping "(c1) extraEnv double-maps the extension directory" \
  DATAPIPELINES_DUCKDB_EXTENSION_DIRECTORY /x \
  'set duckdb.extensionDirectory, not extraEnv.DATAPIPELINES_DUCKDB_EXTENSION_DIRECTORY' \
  "$WORK/c1.out" "$WORK/c1.err"
check_double_mapping "(c2) extraEnv double-maps the memory limit" \
  DATAPIPELINES_DUCKDB_MEMORY_LIMIT 4GB \
  'set duckdb.memoryLimit, not extraEnv.DATAPIPELINES_DUCKDB_MEMORY_LIMIT' \
  "$WORK/c2.out" "$WORK/c2.err"

# (d) no secret name — the placeholder OMITTED; pins the `required` guard.
rc=0
"$HELM" template dp "$CHART_DIR" >"$WORK/d.out" 2>"$WORK/d.err" || rc=$?
if [ "$rc" -eq 0 ]; then
  verdict "(d) no secret name" 1 "helm template exited 0 (expected the required-value refusal)"
elif grep -qF 'existingSecret is required' "$WORK/d.err"; then
  verdict "(d) no secret name" 0 "exit $rc with the required-value refusal"
else
  verdict "(d) no secret name" 1 "exit $rc but 'existingSecret is required' is NOT on stderr"
fi

if [ "$fails" -eq 0 ]; then
  echo "helm-check: PASS — all cases green (helm ${HELM_VERSION}, chart ${CHART_DIR#"$ROOT"/})"
  exit 0
fi
echo "helm-check: $fails case(s) FAILED — render files follow for the log" >&2
for f in "$WORK"/*.out "$WORK"/*.err; do
  [ -e "$f" ] || continue
  echo "helm-check: ===== $f =====" >&2
  cat "$f" >&2
done
exit 1
