#!/usr/bin/env bash
# helm-check.sh — render the reference Helm chart (deploy/helm/datapipelines)
# with a PINNED helm binary: `helm lint --strict` plus ten `helm template`
# cases whose assertions pin the chart's render-TIME behaviour — the Kotlin
# drift tests HelmDuckDbValuesSpecDriftTest and HelmScriptJsValuesSpecDriftTest
# pin only the chart's TEXT (the pregate has no helm binary), so the real
# engine is exercised here (#411, closing the text-vs-engine gap #140 left).
#
#   ./scripts/helm-check.sh        # from the repo root; see EXIT CONTRACT
#
# The cases (one PASS/FAIL line each, naming the assertion that decided it):
#   lint  `helm lint --strict` with the placeholder secret. If --strict is red
#         on the untouched chart, the output is logged, lint re-runs WITHOUT
#         --strict and its warnings are REPORTED — the chart is the thing
#         under test and is never edited here; the case fails only if even the
#         non-strict lint exits non-zero.
#   (a)   defaults: exit 0; NO DuckDB or JavaScript env entry, NO rendered
#         `value: ""`, no `env:` block at all (an empty entry would override the
#         image's own DATAPIPELINES_DUCKDB_EXTENSION_DIRECTORY or
#         DATAPIPELINES_SCRIPT_JS_ISOLATE_DIR — the #140/#688 trap); no /app/lib.
#   (b)   both duckdb values set: exit 0; each env name exactly once, with its
#         quoted value; no rendered `value: ""`.
#   (b2)  all nine script.js values set: exit 0; each env name exactly once,
#         with its quoted value. (b2-single) maxHeapMb ALONE: the env: block
#         still renders, with exactly that one entry — pins the or-clause that
#         decides whether the block renders at all.
#   (c)   the extraEnv double mapping, ELEVEN times (the two DuckDB keys and the
#         nine DATAPIPELINES_SCRIPT_JS_* keys): each render exits non-zero AND
#         stderr carries the template's own `fail` message VERBATIM
#         (deployment.yaml) — never just a non-zero exit, which (d) shows fires
#         for the wrong reason too.
#   (d)   no secret name (the placeholder OMITTED): exit non-zero AND stderr
#         carries `existingSecret is required` — pins the `required` guard and
#         proves the placeholder is load-bearing for (a)–(c).
#   (e)   extraEnv.LOADER_PATH: exit non-zero with the exact refusal message; (e2) the
#         same for loader_path, loader.path and Loader_Path, the spellings the launcher reads.
#   (e3)  extraEnv.JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, _JAVA_OPTIONS: each refused with the
#         template's own message (a -D in any of them can override the launcher path).
#   (g)   script.js.isolateDir outside the image's launcher-path entries: refused
#         with the template's own message; the image's own two entries
#         (/opt/graaljs, /app/lib) render.
#   (f)   libVolume enabled with a ConfigMap: exactly one read-only /app/lib
#         mount and one driver-library volume using that ConfigMap.
#         PVC sources must also be read-only; empty/multiple sources refused.
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
  n_js=$(grep -c 'DATAPIPELINES_SCRIPT_JS_' "$WORK/a.out" || true)
  n_emp=$(grep -c 'value: ""' "$WORK/a.out" || true)
  n_env=$(grep -c '^ *env:' "$WORK/a.out" || true)
  n_lib=$(grep -c '/app/lib' "$WORK/a.out" || true)
  n_driver=$(grep -c 'name: driver-library$' "$WORK/a.out" || true)
  if [ "$n_dbu" -ne 0 ]; then
    verdict "(a) defaults" 1 "grep -c 'DATAPIPELINES_DUCKDB_' = $n_dbu (expected 0 — an empty entry would override the image's ENV)"
  elif [ "$n_js" -ne 0 ]; then
    verdict "(a) defaults" 1 "grep -c 'DATAPIPELINES_SCRIPT_JS_' = $n_js (expected 0 — an empty entry would override the image's ISOLATE_DIR)"
  elif [ "$n_emp" -ne 0 ]; then
    verdict "(a) defaults" 1 "grep -c 'value: \"\"' = $n_emp (expected 0 — an empty env value still SETS the variable)"
  elif [ "$n_env" -ne 0 ]; then
    verdict "(a) defaults" 1 "grep -c '^ *env:' = $n_env (expected 0 — the env: block renders only when a source is set)"
  elif [ "$n_lib" -ne 0 ] || [ "$n_driver" -ne 0 ]; then
    verdict "(a) defaults" 1 "/app/lib count=$n_lib; driver-library count=$n_driver (expected 0 each)"
  else
    verdict "(a) defaults" 0 "exit 0; 0 DuckDB entries, 0 JavaScript entries, 0 rendered empty values, no env: block, no /app/lib or driver-library"
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

# (b2) all nine script.js values set — each env name exactly once, quoted value.
# isolateDir is set to /app/lib: one of the image's own launcher-path entries,
# so the render-refusal must let it through.
rc=0
"$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
  --set script.js.isolateDir=/app/lib --set script.js.maxHeapMb=512 \
  --set script.js.isolateMemoryMb=2048 --set script.js.maxStatements=50000000 \
  --set script.js.maxAstDepth=501 --set script.js.maxStackFrames=502 \
  --set script.js.maxOutputBytes=65536 --set script.js.maxResultMb=331 \
  --set script.js.maxInputMb=321 >"$WORK/b2.out" 2>"$WORK/b2.err" || rc=$?
b2_pairs='DATAPIPELINES_SCRIPT_JS_ISOLATE_DIR|/app/lib
DATAPIPELINES_SCRIPT_JS_MAX_HEAP_MB|512
DATAPIPELINES_SCRIPT_JS_ISOLATE_MEMORY_MB|2048
DATAPIPELINES_SCRIPT_JS_MAX_STATEMENTS|50000000
DATAPIPELINES_SCRIPT_JS_MAX_AST_DEPTH|501
DATAPIPELINES_SCRIPT_JS_MAX_STACK_FRAMES|502
DATAPIPELINES_SCRIPT_JS_MAX_OUTPUT_BYTES|65536
DATAPIPELINES_SCRIPT_JS_MAX_RESULT_MB|331
DATAPIPELINES_SCRIPT_JS_MAX_INPUT_MB|321'
if [ "$rc" -ne 0 ]; then
  verdict "(b2) all nine script.js values set" 1 "helm template exited $rc (expected 0)"
else
  b2_bad=""
  while IFS='|' read -r name val; do
    n_name=$(grep -c "^ *- name: $name$" "$WORK/b2.out" || true)
    n_val=$(grep -cF "value: \"$val\"" "$WORK/b2.out" || true)
    if [ "$n_name" -ne 1 ] || [ "$n_val" -ne 1 ]; then
      b2_bad="$b2_bad $name(name=$n_name,value=$n_val)"
    fi
  done <<<"$b2_pairs"
  n_emp=$(grep -c 'value: ""' "$WORK/b2.out" || true)
  if [ -n "$b2_bad" ]; then
    verdict "(b2) all nine script.js values set" 1 "expected each name exactly once with its quoted value;$b2_bad"
  elif [ "$n_emp" -ne 0 ]; then
    verdict "(b2) all nine script.js values set" 1 "grep -c 'value: \"\"' = $n_emp (expected 0)"
  else
    verdict "(b2) all nine script.js values set" 0 "exit 0; each of the nine names exactly once with its quoted value"
  fi
fi

# (b2-single) maxHeapMb ALONE — the env: block must still render, with exactly
# that one entry. Red proof for the or-clause: remove
# .Values.script.js.maxHeapMb from it and no env: block renders at all.
rc=0
"$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
  --set script.js.maxHeapMb=512 >"$WORK/b2s.out" 2>"$WORK/b2s.err" || rc=$?
if [ "$rc" -ne 0 ]; then
  verdict "(b2-single) maxHeapMb alone" 1 "helm template exited $rc (expected 0)"
else
  n_env=$(grep -c '^ *env:' "$WORK/b2s.out" || true)
  n_heap=$(grep -c '^ *- name: DATAPIPELINES_SCRIPT_JS_MAX_HEAP_MB$' "$WORK/b2s.out" || true)
  n_vheap=$(grep -cF 'value: "512"' "$WORK/b2s.out" || true)
  n_other=$(grep -c 'DATAPIPELINES_SCRIPT_JS_' "$WORK/b2s.out" || true)
  if [ "$n_env" -lt 1 ]; then
    verdict "(b2-single) maxHeapMb alone" 1 "no env: block rendered (the or-clause dropped script.js.maxHeapMb?)"
  elif [ "$n_heap" -ne 1 ] || [ "$n_vheap" -ne 1 ]; then
    verdict "(b2-single) maxHeapMb alone" 1 "MAX_HEAP_MB named $n_heap time(s), value \"512\" found $n_vheap time(s) (expected 1 each)"
  elif [ "$n_other" -ne 1 ]; then
    verdict "(b2-single) maxHeapMb alone" 1 "grep -c 'DATAPIPELINES_SCRIPT_JS_' = $n_other (expected exactly the one entry's name line)"
  else
    verdict "(b2-single) maxHeapMb alone" 0 "exit 0; the env: block renders with exactly the one guarded entry"
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

# (c3)..(c11) the nine JavaScript keys, in application.yml order — each
# extraEnv double mapping refused with the template's own message, verbatim.
c_seq=3
while IFS='|' read -r camel envvar; do
  check_double_mapping "(c$c_seq) extraEnv double-maps $camel" \
    "$envvar" /x \
    "set script.js.$camel, not extraEnv.$envvar" \
    "$WORK/c$c_seq.out" "$WORK/c$c_seq.err"
  c_seq=$((c_seq + 1))
done <<'JS_KEYS'
isolateDir|DATAPIPELINES_SCRIPT_JS_ISOLATE_DIR
maxHeapMb|DATAPIPELINES_SCRIPT_JS_MAX_HEAP_MB
isolateMemoryMb|DATAPIPELINES_SCRIPT_JS_ISOLATE_MEMORY_MB
maxStatements|DATAPIPELINES_SCRIPT_JS_MAX_STATEMENTS
maxAstDepth|DATAPIPELINES_SCRIPT_JS_MAX_AST_DEPTH
maxStackFrames|DATAPIPELINES_SCRIPT_JS_MAX_STACK_FRAMES
maxOutputBytes|DATAPIPELINES_SCRIPT_JS_MAX_OUTPUT_BYTES
maxResultMb|DATAPIPELINES_SCRIPT_JS_MAX_RESULT_MB
maxInputMb|DATAPIPELINES_SCRIPT_JS_MAX_INPUT_MB
JS_KEYS

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

check_double_mapping "(e) extraEnv names LOADER_PATH" LOADER_PATH /tmp/x \
  'LOADER_PATH comes from the image, never from extraEnv.LOADER_PATH (deployment.md §9)' \
  "$WORK/e.out" "$WORK/e.err"

# (e2) Spring Boot's launcher reads loader.path from the environment as loader.path,
# loader_path or LOADER_PATH (launch.SystemPropertyUtils: as given, dots to underscores,
# then upper-cased), so the refusal must match every spelling. --set-json, because --set
# would nest a dotted key.
for key in loader_path loader.path Loader_Path; do
  rc=0
  "$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
    --set-json "extraEnv={\"$key\":\"/tmp/x\"}" >"$WORK/e2.out" 2>"$WORK/e2.err" || rc=$?
  if [ "$rc" -eq 0 ]; then
    verdict "(e2) extraEnv names $key" 1 "helm template exited 0 (expected the render-time refusal)"
  elif grep -qF 'LOADER_PATH comes from the image, never from extraEnv.LOADER_PATH (deployment.md §9)' "$WORK/e2.err"; then
    verdict "(e2) extraEnv names $key" 0 "exit $rc with the template's own refusal message"
  else
    verdict "(e2) extraEnv names $key" 1 "exit $rc but the exact refusal message is NOT on stderr (red for the wrong reason)"
  fi
done

# (e3) the JVM reads -D options from these three variables, and a system property wins over
# the image's environment value, so each is refused like the compose audit's class 8.
for key in JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS; do
  check_double_mapping "(e3) extraEnv names $key" "$key" /tmp/x \
    "extraEnv.$key is refused: a JVM option variable can override the image's launcher path (deployment.md §9)" \
    "$WORK/e3.out" "$WORK/e3.err"
done

# (g) script.js.isolateDir is NOT an arbitrary path: outside the image's own
# launcher-path entries it is refused at render time with the template's own
# message; the image's two entries (/opt/graaljs, /app/lib) render.
rc=0
"$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
  --set script.js.isolateDir=/opt/custom >"$WORK/g.out" 2>"$WORK/g.err" || rc=$?
if [ "$rc" -eq 0 ]; then
  verdict "(g) isolateDir outside the image's launcher-path entries" 1 "helm template exited 0 (expected the render-time refusal)"
elif grep -qF "script.js.isolateDir must be one of the image's LOADER_PATH entries (/app/lib,/opt/graaljs) and contain js-isolate.jar (deployment.md §6.4)" "$WORK/g.err"; then
  verdict "(g) isolateDir outside the image's launcher-path entries" 0 "exit $rc with the template's own refusal message"
else
  verdict "(g) isolateDir outside the image's launcher-path entries" 1 "exit $rc but the exact refusal message is NOT on stderr (red for the wrong reason)"
fi
for dir in /opt/graaljs /app/lib; do
  rc=0
  "$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
    --set "script.js.isolateDir=$dir" >"$WORK/g-ok.out" 2>"$WORK/g-ok.err" || rc=$?
  if [ "$rc" -ne 0 ]; then
    verdict "(g) isolateDir $dir renders" 1 "helm template exited $rc (expected 0 — the image's own entry)"
  else
    n_isolate=$(grep -c '^ *- name: DATAPIPELINES_SCRIPT_JS_ISOLATE_DIR$' "$WORK/g-ok.out" || true)
    n_val=$(grep -cF "value: \"$dir\"" "$WORK/g-ok.out" || true)
    if [ "$n_isolate" -ne 1 ] || [ "$n_val" -ne 1 ]; then
      verdict "(g) isolateDir $dir renders" 1 "ISOLATE_DIR named $n_isolate time(s), value found $n_val time(s) (expected 1 each)"
    else
      verdict "(g) isolateDir $dir renders" 0 "exit 0; the entry renders exactly once with its quoted value"
    fi
  fi
done

# (f) the enabled volume: match whole blocks, so an unrelated readOnly elsewhere
# cannot hide a writable mount, a missing source or a misplaced volume.
rc=0
"$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
  --set libVolume.enabled=true --set-json 'libVolume.source={"configMap":{"name":"drivers"}}' \
  >"$WORK/f.out" 2>"$WORK/f.err" || rc=$?
if [ "$rc" -ne 0 ]; then
  verdict "(f) driver library ConfigMap" 1 "helm template exited $rc (expected 0)"
else
  n_lib=$(grep -c 'mountPath: /app/lib$' "$WORK/f.out" || true)
  n_driver=$(grep -c 'name: driver-library$' "$WORK/f.out" || true)
  if [ "$n_lib" -eq 1 ] && [ "$n_driver" -eq 2 ] && awk '
    /^            - name: driver-library$/ {
      if ((getline) > 0 && $0 == "              mountPath: /app/lib" &&
          (getline) > 0 && $0 == "              readOnly: true") mounts++
    }
    /^        - name: driver-library$/ {
      if ((getline) > 0 && $0 == "          configMap:" &&
          (getline) > 0 && $0 == "            name: drivers") volumes++
    }
    END { exit !(mounts == 1 && volumes == 1) }
  ' "$WORK/f.out"; then
    verdict "(f) driver library ConfigMap" 0 "exit 0; exactly one read-only /app/lib mount and one driver-library ConfigMap volume"
  else
    verdict "(f) driver library ConfigMap" 1 "expected exactly one read-only /app/lib mount and one driver-library ConfigMap volume"
  fi
fi

# Additional source-boundary checks: a PVC must carry its own readOnly flag too.
rc=0
"$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
  --set libVolume.enabled=true \
  --set-json 'libVolume.source={"persistentVolumeClaim":{"claimName":"drivers","readOnly":true}}' \
  >"$WORK/f-pvc.out" 2>"$WORK/f-pvc.err" || rc=$?
if [ "$rc" -eq 0 ] && awk '
  /^        - name: driver-library$/ {
    if ((getline) > 0 && $0 == "          persistentVolumeClaim:" &&
        (getline) > 0 && $0 == "            claimName: drivers" &&
        (getline) > 0 && $0 == "            readOnly: true") volumes++
  }
  END { exit !(volumes == 1) }
' "$WORK/f-pvc.out"; then
  verdict "(f-pvc) read-only PVC source" 0 "exit 0; PVC source also carries readOnly: true"
else
  verdict "(f-pvc) read-only PVC source" 1 "exit $rc; expected a PVC source with readOnly: true"
fi

check_lib_source_refusal() {  # <label> <source JSON> <verbatim message>
  local label="$1" source="$2" msg="$3" rc=0
  "$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
    --set libVolume.enabled=true --set-json "libVolume.source=$source" \
    >"$WORK/$label.out" 2>"$WORK/$label.err" || rc=$?
  if [ "$rc" -ne 0 ] && grep -qF "$msg" "$WORK/$label.err"; then
    verdict "(f-$label) invalid library source" 0 "exit $rc with the template's own refusal message"
  else
    verdict "(f-$label) invalid library source" 1 "exit $rc; expected the exact source refusal message"
  fi
}
source_message='libVolume.source must contain exactly one trusted volume source (deployment.md §9)'
check_lib_source_refusal empty '{}' "$source_message"
check_lib_source_refusal multiple '{"configMap":{"name":"drivers"},"emptyDir":{}}' "$source_message"
pvc_message='libVolume.source.persistentVolumeClaim.readOnly must be true (deployment.md §9)'
check_lib_source_refusal writable-pvc '{"persistentVolumeClaim":{"claimName":"drivers","readOnly":false}}' "$pvc_message"
check_lib_source_refusal implicit-pvc '{"persistentVolumeClaim":{"claimName":"drivers"}}' "$pvc_message"
check_lib_source_refusal string-pvc '{"persistentVolumeClaim":{"claimName":"drivers","readOnly":"false"}}' "$pvc_message"

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
