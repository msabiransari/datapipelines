#!/usr/bin/env bash
# helm-check.sh — render the reference Helm chart (deploy/helm/datapipelines)
# with a PINNED helm binary: `helm lint --strict` plus the `helm template`
# cases below — each case prints its own PASS/FAIL `verdict` line, labels
# `(a)`–`(j-r)`; a full green run printed 141 PASS lines at this commit
# (#863 review F1; the count moves with the cases, so re-measure it after adding one) —
# whose assertions pin the chart's render-TIME behaviour, and validate every
# successful render against the Kubernetes API schema with a PINNED
# kubeconform (the `kubeconform` leg below; #712) — the Kotlin drift
# tests HelmDuckDbValuesSpecDriftTest, HelmScriptJsValuesSpecDriftTest and
# HelmManagementValuesSpecDriftTest pin only the chart's TEXT (the pregate has
# no helm binary), so the real engine is exercised here (#411, closing the
# text-vs-engine gap #140 left).
#
#   ./scripts/helm-check.sh        # from the repo root; see EXIT CONTRACT
#
# The kubeconform leg: EVERY case that renders successfully, including the
# egress and availability cases, gets one more verdict, `<case> kubeconform`: the render
# validated -strict against the Kubernetes 1.35.0 schema and the CRDs-catalog
# (ServiceMonitor), every object accounted for. `helm template` is not API
# validation: round one's (h2) rendered an invalid LabelSelector green.
#
# The cases (one PASS/FAIL line each, naming the assertion that decided it):
#   lint  `helm lint --strict` with the placeholder secret. If --strict is red
#         on the untouched chart, the output is logged, lint re-runs WITHOUT
#         --strict and its warnings are REPORTED — the chart is the thing
#         under test and is never edited here; the case fails only if even the
#         non-strict lint exits non-zero.
#   (a)   defaults: exit 0; NO DuckDB or JavaScript env entry, NO rendered
#         `value: ""`, and the env: blocks hold EXACTLY the egress-proxy
#         entries (#845) plus the renderer init container's six inputs
#         (#862; five are optional Secret key references) — nothing else (an empty entry would override the image's own
#         DATAPIPELINES_DUCKDB_EXTENSION_DIRECTORY or
#         DATAPIPELINES_SCRIPT_JS_ISOLATE_DIR — the #140/#688 trap); no /app/lib.
#   (a-off) lake.egressProxy.enabled=false: the pre-#845 contract — no `env:`
#         block at all (the init container renders only with the proxy).
#         (b2-single) and (b2-null) render with the proxy OFF too,
#         so the env block's or-clause stays the thing they pin.
#   (b)   both duckdb values set: exit 0; each env name exactly once, with its
#         quoted value; no rendered `value: ""`.
#   (b2)  all nine script.js values set: exit 0; each env name exactly once,
#         with its quoted value. (b2-single) maxHeapMb ALONE: the env: block
#         still renders, with exactly that one entry — pins the or-clause that
#         decides whether the block renders at all. (b2-zero) each sizing key alone
#         as numeric zero renders quoted "0" for startup validation; null omits it.
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
#   (h)   management defaults: exit 0; no 9090, no MANAGEMENT_* env entry, no
#         NetworkPolicy, no ServiceMonitor anywhere (the management block,
#         configuration.md §3.14, is OFF by default and the render must not
#         differ from a chart without it).
#   (h2)  management.expose with the NetworkPolicy, a scraper namespace
#         selector and one app-port peer (an ingress-nginx namespace): exit 0;
#         each assertion its own verdict — exactly one containerPort 9090
#         named `management`, exactly one MANAGEMENT_SERVER_PORT entry,
#         exactly one MANAGEMENT_SERVER_ADDRESS valued "0.0.0.0" (the opt-open
#         switch, configuration.md §3.14), one headless Service
#         (clusterIP: None, port `management`), the NetworkPolicy ingress
#         contract (np_contract below: the rendered stream PARSED — the
#         scraper rule on the management port AND the app-port rule from the
#         declared peers), and NO ServiceMonitor.
#   (h2b) appIngress.allowAll: true — the contract with a from-less app rule,
#         and the acknowledgement printed once as a comment line.
#   (h2c) both scraper selectors (two separate, OR'd peers; matchExpressions)
#         and app peers mixing a podSelector and an ipBlock — the contract.
#   (h3)  serviceMonitor.enabled adds exactly one ServiceMonitor
#         (monitoring.coreos.com/v1, selector = the headless Service's labels,
#         endpoint port `management`, path /actuator/prometheus, interval from
#         the values).
#   (h4)  the extraEnv double mapping for the two MANAGEMENT_SERVER_* keys:
#         each refused with the template's own `fail` message verbatim —
#         Kubernetes honours the last duplicate env name silently, and the
#         0.0.0.0 address must come only from management.expose.
#   (h5)  networkPolicy.enabled with BOTH scraper selectors empty: refused with
#         the template's own message (an empty from: admits every source).
#   (h5b)–(h5h) the policy's other refusals, each with the template's own
#         message verbatim: no app-port declaration (the policy would isolate
#         the app port); allowAll AND a from; a bare label map as the scraper
#         selector (round one's example) and inside an app peer; an unknown
#         peer key; an empty peer; a quoted "true" allowAll.
#   (i)   the managed reads' egress proxy (#845, values.yaml lake.egressProxy,
#         ON by default) — egress_contract below, the rendered stream PARSED:
#         exactly one proxy Deployment, Service and (unless its
#         networkPolicy is off) NetworkPolicy, nothing else carrying the
#         component label — NO ConfigMap: since #862 the config is not chart
#         text but the renderer init container's output, so the contract pins
#         THE WIRING instead: the init container runs the app image's renderer
#         entrypoint, byte-equal to deploy/compose.yml's egress-config
#         entrypoint (the one-config rule), fed the chart's max-age and ONLY
#         the five renderer names, each extraEnv's value when set there, else
#         an optional key reference to the same-named existingSecret key — the
#         app container's precedence; no envFrom (least privilege, review R1),
#         writing an emptyDir that Envoy mounts read-only at /etc/envoy; the
#         proxy and app selectors select only their own pods; the image equals
#         deploy/compose.yml's egress-proxy image; compose's egress-proxy is
#         read_only (the #862 accommodation is gone), drops every capability,
#         and depends on the renderer's successful completion; a changed
#         renderer input rolls the pods via checksum/egress-render-inputs;
#         the app's two env entries name the proxy Service and the same
#         max-age; the policy admits only the app pods on the proxy port, and
#         restricts egress only when the store is declared (then store rule +
#         DNS rule, verbatim). (The runtime output itself — the renderer's
#         envoy.yaml, its listener port — is compose-env-audit's and the E2E's
#         to parse; a render here sees only the wiring.)
#         Renders: (i) defaults, (i2) store to+ports with another max-age,
#         (i3) ports-only with dnsTo, (i4) its policy off, (i5) the proxy off
#         (no proxy object, no egress env entry; an extraEnv proxy is then
#         the operator's and renders once).
#   (j)   #863 availability (values.yaml lake.egressProxy
#         podDisruptionBudget / topologySpreadConstraints, both OFF by
#         default): the documented two-replica example renders a policy/v1
#         PDB (minAvailable from the values, strictly below replicas at
#         render — minAvailable = replicas deadlocks every voluntary
#         eviction) and one topologySpreadConstraint (maxSkew /
#         topologyKey / whenUnsatisfiable from the values) whose
#         labelSelector is ALWAYS the proxy Deployment's own selector —
#         never the app's, never operator-supplied; replicas render from the
#         validated replicaCount; the readiness probe is retained; the app
#         Deployment gains no availability objects of its own. (j2) the
#         spread alone (no PDB object). (j3) Kubernetes int32 upper bounds;
#         (j4/j5) disabled availability/proxy fields stay inert.
#   (j-r) #863 refusals, each with the template's own message verbatim:
#         replicaCount 0 / fractional / quoted / int32 overflow; minAvailable = replicaCount
#         (the drain deadlock), below 1, quoted, and enabled on the
#         one-replica default (the single-node deadlock); maxSkew 0 / int32 overflow; an
#         unknown whenUnsatisfiable; an empty topologyKey. Disabled flags
#         stay inert — a disabled budget/spread with nonsense fields renders.
#   (i7)  the renderer inputs RESOLVED (#862 review R1): fixture Secrets with
#         dummy credentials, both containers' env resolved the kubelet's way,
#         the six inputs identical in app and init and equal to the case's
#         literal expectation, no other Secret key in the init container —
#         Secret only, extraEnv override, partial Secret + AWS region, nothing
#         set, an alias in the Secret (app only); HELM_CHECK_RENDERER_JAR also
#         runs the real renderer on both sides (egress_resolve).
#   (i6)  refusals, each with the template's own message verbatim: the two
#         extraEnv double mappings while enabled; the four relaxed-binding
#         lake-storage aliases (they bind in the app but never reach the
#         renderer — #862); maxAgeSeconds 0, quoted and
#         fractional; a tag-only image; a bare label map, an unknown key and
#         a non-list in the policy's peers.
#
# Tools: helm and kubeconform, both PINNED (kubeconform's pin is described
# beside KUBECONFORM_VERSION; it follows the same discipline), plus python3
# with PyYAML for the parsed NetworkPolicy contract. CI-only by the owner's
# ruling (2026-10-02): the pregate
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
#       missing chart, python3/PyYAML absent, a kubeconform schema fetch that
#       failed (or an apiVersion/kind with no schema), a kubeconform report
#       that does not account for every rendered object, any unhandled tooling
#       error — no verdict
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

KUBECONFORM_VERSION="v0.8.0"   # verified latest release (github.com/yannh/kubeconform, REST releases/latest: published 2026-06-04), 2026-10-25
# #712 — the SHA-256 of the v0.8.0 release's CHECKSUMS, computed 2026-10-25
# 17:25 UTC from a fresh download of
#   https://github.com/yannh/kubeconform/releases/download/v0.8.0/CHECKSUMS
# (the linux-amd64 tarball fetched with it matched its CHECKSUMS line).
# kubeconform publishes ONE multi-platform manifest in sha256sum format (the
# osv-scanner shape, not helm's per-asset .sha256sum), so this one pin covers
# every platform's tarball. Refresh it WITH the version, in one commit
# (DEVELOPMENT.md §10.2, "Bumping kubeconform").
KUBECONFORM_SUMS_SHA256="235fc6d8801ca0b6dba3ff3d49b8751e27c7ec92f4414fe052b283ec50cca0af"
KC_TOOL_DIR="$(scan_tools_dir kubeconform)"
# The release names its tarballs WITHOUT the version; the cache stores each
# under a versioned name so scan_tools_prune's <tool>-<version>-<platform>
# grammar can retire a superseded version (and its .CHECKSUMS sidecar).
KC_ASSET="kubeconform-${os}-${arch}.tar.gz"
KC_TARBALL="$KC_TOOL_DIR/kubeconform-${KUBECONFORM_VERSION}-${os}-${arch}.tar.gz"
KC_SUMS="$KC_TARBALL.CHECKSUMS"
KC_RELEASE_BASE="https://github.com/yannh/kubeconform/releases/download/${KUBECONFORM_VERSION}"
# The schemas every successful render is validated against, fetched at check
# time (the gate job is online) from COMMIT-pinned raw URLs — never a branch
# head, so a verdict cannot change without a commit here. The first is
# kubeconform's own `default` location (README, "-schema-location") with
# `master` replaced by a commit; the second is the README's Datree
# CRDs-catalog template, which carries the ServiceMonitor schema
# (monitoring.coreos.com/v1). Both commits are the branch heads read
# 2026-10-25 (REST API); bump them together with the Kubernetes version.
KC_KUBERNETES_VERSION="1.35.0"
KC_SCHEMA_CORE="https://raw.githubusercontent.com/yannh/kubernetes-json-schema/f7d855e49c34b039c9ef2196444910275e31e09e/{{.NormalizedKubernetesVersion}}-standalone{{.StrictSuffix}}/{{.ResourceKind}}{{.KindSuffix}}.json"
KC_SCHEMA_CRDS="https://raw.githubusercontent.com/datreeio/CRDs-catalog/3eb90d47aba8d61919d4419dd38301536e32a94b/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json"

# The manifest's own integrity: the cached or freshly downloaded .sha256sum
# must hash to the in-repo pin BEFORE it is used to verify the tarball (#198).
# A mismatch exits 2 printing both hashes and deletes the manifest so the next
# online run re-downloads it rather than caching a bad one.
verify_manifest_pin() {  # <manifest> <in-repo pin> <manifest label> — helm's and kubeconform's
  local sums="$1" pin="$2" label="$3" got
  got=$(shasum -a 256 "$sums" 2>/dev/null | awk '{print $1}') || true
  if [ "$got" != "$pin" ]; then
    echo "helm-check: FAIL — $label does not match the in-repo pin (pin=$pin got=$got); a swapped manifest is a supply-chain failure, not a chart verdict." >&2
    rm -f "$sums"
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
  verify_manifest_pin "$SUMS" "$HELM_SUMS_SHA256" "$ASSET.sha256sum"
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

# kubeconform — the same discipline as helm, against its one multi-platform
# CHECKSUMS manifest: pin first, then the tarball's line, then the prune.
install_kubeconform() {
  mkdir -p "$KC_TOOL_DIR"
  echo "helm-check: installing kubeconform ${KUBECONFORM_VERSION} (${KC_ASSET}) into $KC_TOOL_DIR"
  echo "helm-check:   tarball:  $KC_RELEASE_BASE/$KC_ASSET"
  echo "helm-check:   manifest: $KC_RELEASE_BASE/CHECKSUMS"
  scan_tools_download helm-check "$KC_RELEASE_BASE/$KC_ASSET" "$KC_TARBALL"
  scan_tools_download helm-check "$KC_RELEASE_BASE/CHECKSUMS" "$KC_SUMS"
  verify_manifest_pin "$KC_SUMS" "$KUBECONFORM_SUMS_SHA256" "kubeconform ${KUBECONFORM_VERSION} CHECKSUMS"
  scan_tools_verify_sha256 helm-check "$KC_TARBALL" "$KC_SUMS" "$KC_ASSET"
  scan_tools_prune helm-check kubeconform "$KC_TARBALL"
}

kubeconform_cached() {
  [ -f "$KC_TARBALL" ] && [ -f "$KC_SUMS" ]
}

[ -d "$CHART_DIR" ] || { echo "helm-check: FAIL — $CHART_DIR is missing; nothing to render (tooling, not a verdict)" >&2; exit 2; }

helm_cached || install_helm
# Every run, not only the install (#193): the cache is machine-level and
# long-lived, so the tarball is checked against the manifest cached beside it,
# after the manifest is checked against the in-repo pin (#198), before
# anything is extracted. A mismatch prints both hashes and exits 2.
verify_manifest_pin "$SUMS" "$HELM_SUMS_SHA256" "$ASSET.sha256sum"
scan_tools_verify_sha256 helm-check "$TARBALL" "$SUMS" "$ASSET"
echo "helm-check: helm ${HELM_VERSION} verified (manifest pin + tarball SHA-256)"

kubeconform_cached || install_kubeconform
# Every run, as for helm (#193/#198).
verify_manifest_pin "$KC_SUMS" "$KUBECONFORM_SUMS_SHA256" "kubeconform ${KUBECONFORM_VERSION} CHECKSUMS"
scan_tools_verify_sha256 helm-check "$KC_TARBALL" "$KC_SUMS" "$KC_ASSET"
echo "helm-check: kubeconform ${KUBECONFORM_VERSION} verified (manifest pin + tarball SHA-256)"

# The NetworkPolicy contract verdict parses the rendered stream with python3 +
# PyYAML (ubuntu-latest ships both). Absent = tooling, never a verdict.
python3 -c 'import yaml' || { echo "helm-check: FAIL — python3 with PyYAML is required to parse the rendered NetworkPolicy (tooling, not a verdict)" >&2; exit 2; }

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
# kubeconform extracted fresh too (the tarball holds the binary at its root);
# its schema cache lives in $WORK, so schemas are fetched once per run and
# never trusted across runs.
mkdir -p "$WORK/kubeconform" "$WORK/kubeconform-cache"
tar -xzf "$KC_TARBALL" -C "$WORK/kubeconform"
KUBECONFORM="$WORK/kubeconform/kubeconform"
[ -x "$KUBECONFORM" ] || { echo "helm-check: FAIL — $KC_ASSET did not contain kubeconform at its root (packaging layout changed?)" >&2; exit 2; }
KC_CACHE="$WORK/kubeconform-cache"

fails=0
verdict() {  # verdict <case-label> <ok:0|1> <the assertion that decided it>
  if [ "$2" -eq 0 ]; then
    echo "PASS: $1 — $3"
  else
    echo "FAIL: $1 — $3" >&2
    fails=$((fails + 1))
  fi
}

# kubeconform leg (#712 lander finding 1) — every SUCCESSFUL render is
# validated against the Kubernetes API schema (-strict: no additional
# properties, no duplicate keys) and, for the ServiceMonitor, the
# CRDs-catalog: `helm template` renders text, it does not validate it.
# kubeconform's own exit code conflates an invalid document with a tooling
# error (a missing -cache folder exits 1 as well — measured 2026-10-25), so the
# outcome is read per object from its -verbose JSON report. The render goes in
# on STDIN: given a path without a .yaml/.yml/.json extension (every *.out
# here) kubeconform validates NOTHING and exits 0 (valid=0 — measured
# 2026-10-25), which the object-count check below refuses as tooling:
#   statusInvalid on any object        → verdict 1 naming the object, the
#                                        schema path and kubeconform's message
#   statusError (a schema fetch failed, or no schema exists for that
#   apiVersion/kind), an unreadable report, an object count that differs from
#   the render's own document count, or a status/exit disagreement
#                                      → exit 2: tooling, no verdict
kc_validate() {  # <case-label> <rendered-file>
  local label="$1" file="$2" rc=0 prc=0 report
  "$KUBECONFORM" -strict -kubernetes-version "$KC_KUBERNETES_VERSION" \
    -schema-location "$KC_SCHEMA_CORE" -schema-location "$KC_SCHEMA_CRDS" \
    -cache "$KC_CACHE" -summary -verbose -output json - \
    <"$file" >"$file.kc.json" 2>"$file.kc.err" || rc=$?
  # trap - ERR in the substitution (018/F3): the program's verdict-bearing
  # exit 1 is handled below and must not fire the inherited ERR trap.
  report=$(trap - ERR; python3 - "$file" "$file.kc.json" "$rc" "$KC_KUBERNETES_VERSION" <<'PY'
import json
import sys

import yaml


def main():
    render, report_path, kc_rc, k8s = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
    with open(render, encoding="utf-8") as f:
        docs = [d for d in yaml.safe_load_all(f) if d is not None]
    try:
        with open(report_path, encoding="utf-8") as f:
            report = json.load(f)
    except (OSError, ValueError) as e:
        print(f"no readable JSON report (kubeconform exit {kc_rc}): {e}")
        sys.exit(2)
    resources = report.get("resources") or []
    s = report.get("summary") or {}
    counts = (f"valid={s.get('valid')} invalid={s.get('invalid')} "
              f"errors={s.get('errors')} skipped={s.get('skipped')}")
    errors = [r for r in resources if r.get("status") == "statusError"]
    if errors:
        print("; ".join(f"{r.get('kind')} {r.get('name')}: {r.get('msg')}" for r in errors) + f" ({counts})")
        sys.exit(2)
    if not docs:
        print("the render holds no objects (nothing to validate)")
        sys.exit(1)
    if len(resources) != len(docs):
        print(f"kubeconform reported {len(resources)} object(s), the render holds {len(docs)} ({counts})")
        sys.exit(2)
    invalid = [r for r in resources if r.get("status") == "statusInvalid"]
    if invalid:
        found = []
        for r in invalid:
            details = r.get("validationErrors") or [{"path": "?", "msg": r.get("msg")}]
            found += [f"{r.get('kind')} {r.get('name')} at {e.get('path')}: {e.get('msg')}" for e in details]
        print("; ".join(found) + f" ({counts})")
        sys.exit(1)
    if any(r.get("status") != "statusValid" for r in resources) or kc_rc != 0 or s.get("valid") != len(docs):
        print(f"statuses and exit {kc_rc} disagree ({counts})")
        sys.exit(2)
    print(f"{len(docs)}/{len(docs)} objects valid against the Kubernetes {k8s} schema (strict) — {counts}")


# An uncaught exception would exit 1 — a VERDICT code; a crash is tooling (2).
try:
    main()
except SystemExit:
    raise
except Exception as e:  # noqa: BLE001
    print(f"crashed: {e!r}")
    sys.exit(2)
PY
) || prc=$?
  case "$prc" in
    0) verdict "$label kubeconform" 0 "$report" ;;
    1) verdict "$label kubeconform" 1 "$report" ;;
    *)
      echo "helm-check: kubeconform could not validate $label — $report (tooling, not a verdict)" >&2
      cat "$file.kc.err" >&2
      exit 2
      ;;
  esac
}

# The ingress contract (#712 lander finding 2), independent of kubeconform: a
# schema-valid policy can still cut the app port off — a NetworkPolicy that
# selects the app pods isolates EVERY ingress to them, and the allow rules of
# all policies are unioned. The rendered stream is PARSED (python3 + PyYAML;
# a text count is not a parse) and held against the values file the case
# rendered with:
#   exactly one NetworkPolicy, selecting the Deployment's pods
#   (podSelector.matchLabels = the Deployment's selector); policyTypes ==
#   [Ingress]; exactly two ingress rules — the scraper rule (from = one peer
#   per non-empty scraper selector, verbatim; ports == [TCP management]) and
#   the app rule (from = appIngress.from verbatim, or NO from under
#   allowAll; ports == [TCP app port]).
# Both ports are read from the rendered Deployment's containerPorts named
# `http` and `management`, never from a literal here.
np_contract() {  # <case-label> <rendered-file> <values-file>
  local label="$1" file="$2" values="$3" prc=0 report
  report=$(trap - ERR; python3 - "$file" "$values" <<'PY'
import sys

import yaml


def main():
    render, values_path = sys.argv[1], sys.argv[2]
    with open(render, encoding="utf-8") as f:
        docs = [d for d in yaml.safe_load_all(f) if d is not None]
    with open(values_path, encoding="utf-8") as f:
        values = yaml.safe_load(f) or {}
    np_values = (values.get("management") or {}).get("networkPolicy") or {}
    app_values = np_values.get("appIngress") or {}

    # The app's objects only: the egress proxy's (#845) carry the component label
    # and have their own contract (egress_contract).
    def app_object(d):
        return ((d.get("metadata") or {}).get("labels") or {}).get("app.kubernetes.io/component") != "egress-proxy"

    deployments = [d for d in docs if d.get("kind") == "Deployment" and app_object(d)]
    policies = [d for d in docs if d.get("kind") == "NetworkPolicy" and app_object(d)]
    if len(deployments) != 1 or len(policies) != 1:
        print(f"{len(deployments)} Deployment(s), {len(policies)} NetworkPolicy object(s) (expected 1 each)")
        sys.exit(1)
    deployment = deployments[0]
    ports = {
        p.get("name"): p.get("containerPort")
        for c in deployment["spec"]["template"]["spec"]["containers"]
        for p in c.get("ports") or []
    }
    app_port, management_port = ports.get("http"), ports.get("management")
    if app_port is None or management_port is None:
        print(f"the Deployment's containerPorts {ports} lack http or management")
        sys.exit(1)

    spec = policies[0].get("spec") or {}
    problems = []
    if spec.get("podSelector") != {"matchLabels": deployment["spec"]["selector"]["matchLabels"]}:
        problems.append(f"podSelector {spec.get('podSelector')} is not the Deployment's selector")
    if spec.get("policyTypes") != ["Ingress"]:
        problems.append(f"policyTypes {spec.get('policyTypes')} (expected [Ingress])")
    rules = spec.get("ingress") or []
    if len(rules) != 2:
        problems.append(f"{len(rules)} ingress rule(s) (expected 2: scraper → {management_port}, app → {app_port})")


    def tcp(port):
        return [{"protocol": "TCP", "port": port}]


    def admitting(port):
        return [r for r in rules if {"protocol": "TCP", "port": port} in (r.get("ports") or [])]


    expected_scraper = []
    if np_values.get("scraperNamespaceSelector"):
        expected_scraper.append({"namespaceSelector": np_values["scraperNamespaceSelector"]})
    if np_values.get("scraperPodSelector"):
        expected_scraper.append({"podSelector": np_values["scraperPodSelector"]})
    scraper_rules = admitting(management_port)
    if len(scraper_rules) != 1:
        problems.append(f"{len(scraper_rules)} ingress rule(s) admit the management port {management_port} (expected 1)")
    else:
        rule = scraper_rules[0]
        if rule.get("ports") != tcp(management_port):
            problems.append(f"the scraper rule's ports {rule.get('ports')} (expected only TCP {management_port})")
        if rule.get("from") != expected_scraper:
            problems.append(f"the scraper rule's from {rule.get('from')} (expected {expected_scraper})")

    app_rules = admitting(app_port)
    if not app_rules:
        problems.append(f"no ingress rule admits the app port {app_port}")
    elif len(app_rules) != 1:
        problems.append(f"{len(app_rules)} ingress rules admit the app port {app_port} (expected 1)")
    else:
        rule = app_rules[0]
        if rule.get("ports") != tcp(app_port):
            problems.append(f"the app rule's ports {rule.get('ports')} (expected only TCP {app_port})")
        if app_values.get("allowAll") is True:
            if "from" in rule:
                problems.append(f"allowAll renders a from {rule.get('from')} (expected none)")
        elif rule.get("from") != app_values.get("from") or not rule.get("from"):
            problems.append(f"the app rule's from {rule.get('from')} (expected appIngress.from {app_values.get('from')})")

    if problems:
        print("; ".join(problems))
        sys.exit(1)
    mode = "every source (allowAll)" if app_values.get("allowAll") is True else f"{len(rule['from'])} declared peer(s)"
    print(f"one Ingress policy on the app pods: scraper peers → TCP {management_port}; {mode} → TCP {app_port}")


# An uncaught exception would exit 1 — a VERDICT code; a crash is tooling (2).
try:
    main()
except SystemExit:
    raise
except Exception as e:  # noqa: BLE001
    print(f"crashed: {e!r}")
    sys.exit(2)
PY
) || prc=$?
  case "$prc" in
    0) verdict "$label NetworkPolicy ingress contract" 0 "$report" ;;
    1) verdict "$label NetworkPolicy ingress contract" 1 "$report" ;;
    *) echo "helm-check: the NetworkPolicy contract check crashed for $label (exit $prc) — $report (tooling, not a verdict)" >&2; exit 2 ;;
  esac
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
  # Env entry names are the upper-case `- name:` lines (ports, volumes and
  # containers are lower-case); the default-ON egress proxy sets the app's
  # two (#845) and the renderer init container's six inputs (#862: the max-age
  # value plus five optional existingSecret key references, review R1) — the
  # max-age name appears twice, once per container.
  a_names=$(grep -E '^ *- name: [A-Z_][A-Z0-9_]*$' "$WORK/a.out" | sed -E 's/^ *- name: //' | sort | tr '\n' ' ' || true)
  a_expected="DATAPIPELINES_LAKE_ENABLED DATAPIPELINES_LAKE_READS_EGRESS_MAX_AGE_SECONDS DATAPIPELINES_LAKE_READS_EGRESS_MAX_AGE_SECONDS DATAPIPELINES_LAKE_READS_EGRESS_PROXY DATAPIPELINES_LAKE_S3_BUCKET DATAPIPELINES_LAKE_S3_ENDPOINT DATAPIPELINES_LAKE_S3_PATH_STYLE DATAPIPELINES_LAKE_S3_REGION "
  n_lib=$(grep -c '/app/lib' "$WORK/a.out" || true)
  n_driver=$(grep -c 'name: driver-library$' "$WORK/a.out" || true)
  if [ "$n_dbu" -ne 0 ]; then
    verdict "(a) defaults" 1 "grep -c 'DATAPIPELINES_DUCKDB_' = $n_dbu (expected 0 — an empty entry would override the image's ENV)"
  elif [ "$n_js" -ne 0 ]; then
    verdict "(a) defaults" 1 "grep -c 'DATAPIPELINES_SCRIPT_JS_' = $n_js (expected 0 — an empty entry would override the image's ISOLATE_DIR)"
  elif [ "$n_emp" -ne 0 ]; then
    verdict "(a) defaults" 1 "grep -c 'value: \"\"' = $n_emp (expected 0 — an empty env value still SETS the variable)"
  elif [ "$n_env" -ne 2 ] || [ "$a_names" != "$a_expected" ]; then
    verdict "(a) defaults" 1 "env: blocks=$n_env, entry names=[$a_names] (expected 2 blocks — [$a_expected]: the app's two egress entries + the renderer init's six inputs, the max-age named in both)"
  elif [ "$n_lib" -ne 0 ] || [ "$n_driver" -ne 0 ]; then
    verdict "(a) defaults" 1 "/app/lib count=$n_lib; driver-library count=$n_driver (expected 0 each)"
  else
    verdict "(a) defaults" 0 "exit 0; 0 DuckDB entries, 0 JavaScript entries, 0 rendered empty values, the env: blocks hold only the app's two egress entries plus the renderer init's six inputs, no /app/lib or driver-library"
  fi
fi
if [ "$rc" -eq 0 ]; then kc_validate "(a) defaults" "$WORK/a.out"; fi

# (a-off) the egress proxy off — the pre-#845 default contract: no env: block.
rc=0
"$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
  --set lake.egressProxy.enabled=false >"$WORK/a-off.out" 2>"$WORK/a-off.err" || rc=$?
n_env=$(grep -c '^ *env:' "$WORK/a-off.out" || true)
n_emp=$(grep -c 'value: ""' "$WORK/a-off.out" || true)
if [ "$rc" -eq 0 ] && [ "$n_env" -eq 0 ] && [ "$n_emp" -eq 0 ]; then
  verdict "(a-off) egress proxy off" 0 "exit 0; no env: block, no rendered empty value"
else
  verdict "(a-off) egress proxy off" 1 "exit=$rc; env: blocks=$n_env; empty values=$n_emp (expected 0/0/0)"
fi
if [ "$rc" -eq 0 ]; then kc_validate "(a-off) egress proxy off" "$WORK/a-off.out"; fi

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
if [ "$rc" -eq 0 ]; then kc_validate "(b) both set" "$WORK/b.out"; fi

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
if [ "$rc" -eq 0 ]; then kc_validate "(b2) all nine script.js values set" "$WORK/b2.out"; fi

# (b2-single) maxHeapMb ALONE — the env: block must still render, with exactly
# that one entry. Red proof for the or-clause: remove
# .Values.script.js.maxHeapMb from it and no env: block renders at all. The
# egress proxy is OFF here: its entries would render the block regardless.
rc=0
"$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" --set lake.egressProxy.enabled=false \
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
if [ "$rc" -eq 0 ]; then kc_validate "(b2-single) maxHeapMb alone" "$WORK/b2s.out"; fi

# (b2-zero) Explicit numeric zero is supplied, not empty. Pass it to startup
# validation even when it is the only JS key set (#688 landing review F1).
while IFS='|' read -r camel envvar; do
  [ "$camel" != isolateDir ] || continue
  rc=0
  "$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
    --set "script.js.$camel=0" >"$WORK/b2-zero.out" 2>"$WORK/b2-zero.err" || rc=$?
  n_name=$(grep -c "^ *- name: $envvar$" "$WORK/b2-zero.out" || true)
  n_zero=$(grep -cF 'value: "0"' "$WORK/b2-zero.out" || true)
  n_js=$(grep -c 'DATAPIPELINES_SCRIPT_JS_' "$WORK/b2-zero.out" || true)
  if [ "$rc" -eq 0 ] && [ "$n_name" -eq 1 ] && [ "$n_zero" -eq 1 ] && [ "$n_js" -eq 1 ]; then
    verdict "(b2-zero) $camel alone" 0 'explicit zero renders exactly one quoted "0" entry for startup validation'
  else
    verdict "(b2-zero) $camel alone" 1 "exit=$rc; name=$n_name; quoted zero=$n_zero; JS entries=$n_js (expected 0/1/1/1)"
  fi
  if [ "$rc" -eq 0 ]; then kc_validate "(b2-zero) $camel alone" "$WORK/b2-zero.out"; fi
done <<'JS_ZERO_KEYS'
isolateDir|DATAPIPELINES_SCRIPT_JS_ISOLATE_DIR
maxHeapMb|DATAPIPELINES_SCRIPT_JS_MAX_HEAP_MB
isolateMemoryMb|DATAPIPELINES_SCRIPT_JS_ISOLATE_MEMORY_MB
maxStatements|DATAPIPELINES_SCRIPT_JS_MAX_STATEMENTS
maxAstDepth|DATAPIPELINES_SCRIPT_JS_MAX_AST_DEPTH
maxStackFrames|DATAPIPELINES_SCRIPT_JS_MAX_STACK_FRAMES
maxOutputBytes|DATAPIPELINES_SCRIPT_JS_MAX_OUTPUT_BYTES
maxResultMb|DATAPIPELINES_SCRIPT_JS_MAX_RESULT_MB
maxInputMb|DATAPIPELINES_SCRIPT_JS_MAX_INPUT_MB
JS_ZERO_KEYS
# Null is unset; empty strings are already covered by (a).
rc=0
"$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" --set lake.egressProxy.enabled=false \
  --set script.js.maxHeapMb=null >"$WORK/b2-null.out" 2>"$WORK/b2-null.err" || rc=$?
n_js=$(grep -c 'DATAPIPELINES_SCRIPT_JS_' "$WORK/b2-null.out" || true)
n_env=$(grep -c '^ *env:' "$WORK/b2-null.out" || true)
if [ "$rc" -eq 0 ] && [ "$n_js" -eq 0 ] && [ "$n_env" -eq 0 ]; then
  verdict '(b2-null) maxHeapMb unset' 0 'null renders no JS entry or env block'
else
  verdict '(b2-null) maxHeapMb unset' 1 "exit=$rc; JS entries=$n_js; env blocks=$n_env (expected 0 each)"
fi
if [ "$rc" -eq 0 ]; then kc_validate "(b2-null) maxHeapMb unset" "$WORK/b2-null.out"; fi

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
for invalid_dir in /opt/custom 0 false; do
  rc=0
  "$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
    --set "script.js.isolateDir=$invalid_dir" >"$WORK/g.out" 2>"$WORK/g.err" || rc=$?
  if [ "$rc" -eq 0 ]; then
    verdict "(g) isolateDir $invalid_dir outside the image's launcher-path entries" 1 "helm template exited 0 (expected the render-time refusal)"
  elif grep -qF "script.js.isolateDir must be one of the image's LOADER_PATH entries (/app/lib,/opt/graaljs) and contain js-isolate.jar (deployment.md §6.4)" "$WORK/g.err"; then
    verdict "(g) isolateDir $invalid_dir outside the image's launcher-path entries" 0 "exit $rc with the template's own refusal message"
  else
    verdict "(g) isolateDir $invalid_dir outside the image's launcher-path entries" 1 "exit $rc but the exact refusal message is NOT on stderr (red for the wrong reason)"
  fi
done
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
  if [ "$rc" -eq 0 ]; then kc_validate "(g) isolateDir $dir renders" "$WORK/g-ok.out"; fi
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
if [ "$rc" -eq 0 ]; then kc_validate "(f) driver library ConfigMap" "$WORK/f.out"; fi

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
if [ "$rc" -eq 0 ]; then kc_validate "(f-pvc) read-only PVC source" "$WORK/f-pvc.out"; fi

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

# (h) management defaults — the management block (configuration.md §3.14) is
# OFF by default and must render NOTHING: no 9090 anywhere, no MANAGEMENT_* env
# entry, no NetworkPolicy, no ServiceMonitor. Paired with the Kotlin
# HelmManagementValuesSpecDriftTest's textual pins.
rc=0
"$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" >"$WORK/h.out" 2>"$WORK/h.err" || rc=$?
n_9090=$(grep -c '9090' "$WORK/h.out" || true)
n_mgmt_env=$(grep -c 'MANAGEMENT_' "$WORK/h.out" || true)
# The management policy by its name: the egress proxy's own policy (#845) renders by default.
n_np=$(grep -c '^  name: dp-datapipelines-management$' "$WORK/h.out" || true)
n_sm=$(grep -c 'ServiceMonitor' "$WORK/h.out" || true)
if [ "$rc" -ne 0 ]; then
  verdict "(h) management defaults" 1 "helm template exited $rc (expected 0)"
elif [ "$n_9090" -ne 0 ] || [ "$n_mgmt_env" -ne 0 ] || [ "$n_np" -ne 0 ] || [ "$n_sm" -ne 0 ]; then
  verdict "(h) management defaults" 1 "9090=$n_9090 MANAGEMENT_=$n_mgmt_env NetworkPolicy=$n_np ServiceMonitor=$n_sm (expected 0 each — the management block is off by default)"
else
  verdict "(h) management defaults" 0 "exit 0; no 9090, no MANAGEMENT_* env entry, no management NetworkPolicy, no ServiceMonitor"
fi
if [ "$rc" -eq 0 ]; then kc_validate "(h) management defaults" "$WORK/h.out"; fi

# (h2) management exposed — the four objects render (values.yaml, management
# block): the management containerPort, both MANAGEMENT_SERVER_* env entries
# (the address is the opt-open switch, configuration.md §3.14), the headless
# Service and the NetworkPolicy — and no ServiceMonitor. Each assertion its
# own verdict, all against ONE render.
cat >"$WORK/h2.values.yaml" <<'VALUES'
management:
  expose: true
  networkPolicy:
    enabled: true
    scraperNamespaceSelector:
      matchLabels:
        kubernetes.io/metadata.name: monitoring
    appIngress:
      from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: ingress-nginx
VALUES
rc=0
"$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
  -f "$WORK/h2.values.yaml" >"$WORK/h2.out" 2>"$WORK/h2.err" || rc=$?
if [ "$rc" -ne 0 ]; then
  verdict "(h2) management exposed" 1 "helm template exited $rc (expected 0)"
else
  n_cp=$(awk '/^ *- name: management$/{ if ((getline) > 0 && $0 == "              containerPort: 9090") cp++ } END { print cp+0 }' "$WORK/h2.out")
  if [ "$n_cp" -eq 1 ]; then
    verdict "(h2) containerPort management 9090" 0 "exactly one management containerPort: 9090"
  else
    verdict "(h2) containerPort management 9090" 1 "adjacent (name: management, containerPort: 9090) pairs = $n_cp (expected 1)"
  fi
  n_port=$(grep -c '^ *- name: MANAGEMENT_SERVER_PORT$' "$WORK/h2.out" || true)
  n_vport=$(grep -cF 'value: "9090"' "$WORK/h2.out" || true)
  if [ "$n_port" -eq 1 ] && [ "$n_vport" -eq 1 ]; then
    verdict "(h2) MANAGEMENT_SERVER_PORT entry" 0 "named exactly once with its quoted value"
  else
    verdict "(h2) MANAGEMENT_SERVER_PORT entry" 1 "name count=$n_port, quoted value count=$n_vport (expected 1 each)"
  fi
  n_addr=$(grep -c '^ *- name: MANAGEMENT_SERVER_ADDRESS$' "$WORK/h2.out" || true)
  n_vaddr=$(grep -cF 'value: "0.0.0.0"' "$WORK/h2.out" || true)
  if [ "$n_addr" -eq 1 ] && [ "$n_vaddr" -eq 1 ]; then
    verdict "(h2) MANAGEMENT_SERVER_ADDRESS = 0.0.0.0" 0 "named exactly once, valued \"0.0.0.0\" (the opt-open switch)"
  else
    verdict "(h2) MANAGEMENT_SERVER_ADDRESS = 0.0.0.0" 1 "name count=$n_addr, \"0.0.0.0\" value count=$n_vaddr (expected 1 each)"
  fi
  n_mname=$(grep -c '^  name: dp-datapipelines-management$' "$WORK/h2.out" || true)
  n_none=$(grep -c '^  clusterIP: None$' "$WORK/h2.out" || true)
  n_tport=$(grep -c '^      targetPort: management$' "$WORK/h2.out" || true)
  if [ "$n_mname" -eq 2 ] && [ "$n_none" -eq 1 ] && [ "$n_tport" -eq 1 ]; then
    verdict "(h2) headless management Service" 0 "one clusterIP: None Service (port management), named <fullname>-management like the NetworkPolicy"
  else
    verdict "(h2) headless management Service" 1 "<fullname>-management names=$n_mname (Service+NetworkPolicy), clusterIP: None=$n_none, targetPort: management=$n_tport"
  fi
  np_contract "(h2)" "$WORK/h2.out" "$WORK/h2.values.yaml"
  n_sm2=$(grep -c 'ServiceMonitor' "$WORK/h2.out" || true)
  if [ "$n_sm2" -eq 0 ]; then
    verdict "(h2) no ServiceMonitor by default" 0 "serviceMonitor.enabled unset renders none"
  else
    verdict "(h2) no ServiceMonitor by default" 1 "grep -c 'ServiceMonitor' = $n_sm2 (expected 0)"
  fi
fi
if [ "$rc" -eq 0 ]; then kc_validate "(h2) management exposed" "$WORK/h2.out"; fi

# np_render <label> <values-file> <out> <err> — one successful NetworkPolicy
# render: exit 0, then the parsed ingress contract and the kubeconform leg.
np_render() {
  local label="$1" values="$2" out="$3" err="$4" rc=0
  "$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
    -f "$values" >"$out" 2>"$err" || rc=$?
  if [ "$rc" -ne 0 ]; then
    verdict "$label renders" 1 "helm template exited $rc (expected 0)"
    return 0
  fi
  np_contract "$label" "$out" "$values"
  kc_validate "$label" "$out"
}

# (h2b) appIngress.allowAll: true — the app rule renders with NO from (every
# source), and the acknowledgement is printed in the render as a comment line.
cat >"$WORK/h2b.values.yaml" <<'VALUES'
management:
  expose: true
  networkPolicy:
    enabled: true
    scraperNamespaceSelector:
      matchLabels:
        kubernetes.io/metadata.name: monitoring
    appIngress:
      allowAll: true
VALUES
np_render "(h2b) allowAll" "$WORK/h2b.values.yaml" "$WORK/h2b.out" "$WORK/h2b.err"
n_ack=$(grep -c '^    # appIngress.allowAll: true — ' "$WORK/h2b.out" || true)
if [ "$n_ack" -eq 1 ]; then
  verdict "(h2b) allowAll acknowledgement printed" 0 "one '# appIngress.allowAll: true' comment line in the render"
else
  verdict "(h2b) allowAll acknowledgement printed" 1 "acknowledgement comment lines = $n_ack (expected 1)"
fi

# (h2c) both scraper selectors (matchExpressions on the pod one) — two
# SEPARATE peers, OR'd — and app peers mixing a pod selector and an ipBlock,
# each copied verbatim.
cat >"$WORK/h2c.values.yaml" <<'VALUES'
management:
  expose: true
  networkPolicy:
    enabled: true
    scraperNamespaceSelector:
      matchLabels:
        kubernetes.io/metadata.name: monitoring
    scraperPodSelector:
      matchExpressions:
        - key: app.kubernetes.io/name
          operator: In
          values: [prometheus]
    appIngress:
      from:
        - podSelector:
            matchLabels:
              app.kubernetes.io/name: gateway
        - ipBlock:
            cidr: 10.20.0.0/16
            except: [10.20.5.0/24]
VALUES
np_render "(h2c) both selectors + mixed peers" "$WORK/h2c.values.yaml" "$WORK/h2c.out" "$WORK/h2c.err"

# (h3) serviceMonitor.enabled — exactly one ServiceMonitor: monitoring.coreos.com/v1,
# selector = the headless Service's labels, endpoint port management,
# path /actuator/prometheus, interval from the values. The ServiceMonitor doc is
# extracted (from its apiVersion line through the next document separator) so the
# selector greps cannot match another object's labels.
rc=0
"$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
  --set management.expose=true --set management.serviceMonitor.enabled=true \
  >"$WORK/h3.out" 2>"$WORK/h3.err" || rc=$?
awk '
  /^kind: ServiceMonitor$/ { in_sm = 1; print prev; print; next }
  in_sm && /^---$/ { in_sm = 0; next }
  in_sm { print }
  { prev = $0 }
' "$WORK/h3.out" >"$WORK/h3-sm.doc"
if [ "$rc" -ne 0 ]; then
  verdict "(h3) ServiceMonitor renders once" 1 "helm template exited $rc (expected 0)"
else
  n_kind=$(grep -c '^kind: ServiceMonitor$' "$WORK/h3.out" || true)
  h3_api=$(head -1 "$WORK/h3-sm.doc")
  n_selname=$(grep -c '^      app.kubernetes.io/name: datapipelines$' "$WORK/h3-sm.doc" || true)
  n_selinst=$(grep -c '^      app.kubernetes.io/instance: dp$' "$WORK/h3-sm.doc" || true)
  if [ "$n_kind" -eq 1 ] && [ "$h3_api" = "apiVersion: monitoring.coreos.com/v1" ] && [ "$n_selname" -eq 1 ] && [ "$n_selinst" -eq 1 ]; then
    verdict "(h3) ServiceMonitor renders once" 0 "monitoring.coreos.com/v1, selector matches the headless Service's labels"
  else
    verdict "(h3) ServiceMonitor renders once" 1 "kind=$n_kind, first extracted line=$h3_api, selector name=$n_selname instance=$n_selinst (expected 1/monitoring.coreos.com/v1/1/1)"
  fi
  n_eport=$(grep -c '^    - port: management$' "$WORK/h3-sm.doc" || true)
  n_path=$(grep -c '^      path: /actuator/prometheus$' "$WORK/h3-sm.doc" || true)
  n_interval=$(grep -c '^      interval: 30s$' "$WORK/h3-sm.doc" || true)
  if [ "$n_eport" -eq 1 ] && [ "$n_path" -eq 1 ] && [ "$n_interval" -eq 1 ]; then
    verdict "(h3) ServiceMonitor endpoint" 0 "port management, path /actuator/prometheus, interval from the values (30s)"
  else
    verdict "(h3) ServiceMonitor endpoint" 1 "port=$n_eport path=$n_path interval=$n_interval (expected 1 each)"
  fi
fi
if [ "$rc" -eq 0 ]; then kc_validate "(h3) ServiceMonitor" "$WORK/h3.out"; fi

# (h4) the extraEnv double mapping for the two MANAGEMENT_SERVER_* keys — each
# refused with the template's own `fail` message, verbatim (the (c) shape).
check_double_mapping "(h4) extraEnv double-maps MANAGEMENT_SERVER_ADDRESS" \
  MANAGEMENT_SERVER_ADDRESS 0.0.0.0 \
  'set management.expose, not extraEnv.MANAGEMENT_SERVER_ADDRESS (deployment.md §6.4)' \
  "$WORK/h4a.out" "$WORK/h4a.err"
check_double_mapping "(h4) extraEnv double-maps MANAGEMENT_SERVER_PORT" \
  MANAGEMENT_SERVER_PORT 9090 \
  'set management.port, not extraEnv.MANAGEMENT_SERVER_PORT (deployment.md §6.4)' \
  "$WORK/h4b.out" "$WORK/h4b.err"

# (h5) networkPolicy.enabled with BOTH scraper selectors empty — refused with
# the template's own message: an empty from: admits every source.
rc=0
"$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
  --set management.expose=true --set management.networkPolicy.enabled=true \
  >"$WORK/h5.out" 2>"$WORK/h5.err" || rc=$?
if [ "$rc" -ne 0 ] && grep -qF 'management.networkPolicy needs at least one scraper selector — an empty from: admits every source (deployment.md §9)' "$WORK/h5.err"; then
  verdict "(h5) empty scraper selectors refused" 0 "exit $rc with the template's own refusal message"
else
  verdict "(h5) empty scraper selectors refused" 1 "exit $rc; expected the exact empty-from refusal on stderr"
fi

# np_refusal <label> <message> — renders the values on stdin with
# networkPolicy enabled; the render must exit non-zero AND stderr must carry
# the template's own `fail` message verbatim (never just a non-zero exit).
# Generic by body — (j-r)'s availability refusals reuse it too.
np_refusal() {
  local label="$1" msg="$2" slug rc=0
  slug=$(printf '%s' "$label" | tr -c 'a-z0-9' '-')
  cat >"$WORK/$slug.values.yaml"
  "$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
    -f "$WORK/$slug.values.yaml" >"$WORK/$slug.out" 2>"$WORK/$slug.err" || rc=$?
  if [ "$rc" -eq 0 ]; then
    verdict "$label" 1 "helm template exited 0 (expected the render-time refusal)"
  elif grep -qF "$msg" "$WORK/$slug.err"; then
    verdict "$label" 0 "exit $rc with the template's own refusal message"
  else
    verdict "$label" 1 "exit $rc but the exact refusal message is NOT on stderr (red for the wrong reason)"
  fi
}

# (h5b) a scraper selector but NO app-port declaration — the policy would
# isolate the app port, so the render refuses.
np_refusal "(h5b) no app-port declaration refused" \
  'management.networkPolicy isolates every ingress of the app pods — declare management.networkPolicy.appIngress.from or acknowledge management.networkPolicy.appIngress.allowAll: true (deployment.md §6.4)' <<'VALUES'
management:
  expose: true
  networkPolicy:
    enabled: true
    scraperNamespaceSelector:
      matchLabels:
        kubernetes.io/metadata.name: monitoring
VALUES

# (h5c) allowAll: true AND a non-empty from — contradictory, refused.
np_refusal "(h5c) allowAll with from refused" \
  'management.networkPolicy.appIngress sets allowAll: true AND a non-empty from — a contradiction; keep one (deployment.md §6.4)' <<'VALUES'
management:
  expose: true
  networkPolicy:
    enabled: true
    scraperNamespaceSelector:
      matchLabels:
        kubernetes.io/metadata.name: monitoring
    appIngress:
      allowAll: true
      from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: ingress-nginx
VALUES

# (h5d) the round-one example — a bare label map as the scraper selector —
# refused: it is not a LabelSelector (#712 lander finding 1).
np_refusal "(h5d) bare label map scraper selector refused" \
  'management.networkPolicy.scraperNamespaceSelector must be a LabelSelector object — matchLabels / matchExpressions — not a bare label map (found key "kubernetes.io/metadata.name"; deployment.md §6.4)' <<'VALUES'
management:
  expose: true
  networkPolicy:
    enabled: true
    scraperNamespaceSelector:
      kubernetes.io/metadata.name: monitoring
    appIngress:
      allowAll: true
VALUES

# (h5e) the same bare label map inside an app-port peer's selector — refused.
np_refusal "(h5e) bare label map in an app peer refused" \
  'management.networkPolicy.appIngress.from[0].namespaceSelector must be a LabelSelector object — matchLabels / matchExpressions — not a bare label map (found key "kubernetes.io/metadata.name"; deployment.md §6.4)' <<'VALUES'
management:
  expose: true
  networkPolicy:
    enabled: true
    scraperNamespaceSelector:
      matchLabels:
        kubernetes.io/metadata.name: monitoring
    appIngress:
      from:
        - namespaceSelector:
            kubernetes.io/metadata.name: ingress-nginx
VALUES

# (h5f) a peer key that is not a NetworkPolicyPeer field — refused.
np_refusal "(h5f) unknown peer key refused" \
  'management.networkPolicy.appIngress.from[0] carries "namespace" — a NetworkPolicyPeer has only namespaceSelector / podSelector / ipBlock (deployment.md §6.4)' <<'VALUES'
management:
  expose: true
  networkPolicy:
    enabled: true
    scraperNamespaceSelector:
      matchLabels:
        kubernetes.io/metadata.name: monitoring
    appIngress:
      from:
        - namespace: ingress-nginx
VALUES

# (h5g) an empty peer — refused (it would select nothing the operator named).
np_refusal "(h5g) empty peer refused" \
  'management.networkPolicy.appIngress.from[0] must be a NetworkPolicyPeer object — namespaceSelector / podSelector / ipBlock (deployment.md §6.4)' <<'VALUES'
management:
  expose: true
  networkPolicy:
    enabled: true
    scraperNamespaceSelector:
      matchLabels:
        kubernetes.io/metadata.name: monitoring
    appIngress:
      from:
        - {}
VALUES

# (h5h) a quoted "true" — a string, not the boolean acknowledgement — refused
# (the f-string-pvc shape: a quoted boolean is truthy whatever it says).
np_refusal "(h5h) quoted allowAll refused" \
  'management.networkPolicy.appIngress.allowAll must be true or false, unquoted (deployment.md §6.4)' <<'VALUES'
management:
  expose: true
  networkPolicy:
    enabled: true
    scraperNamespaceSelector:
      matchLabels:
        kubernetes.io/metadata.name: monitoring
    appIngress:
      allowAll: "true"
VALUES

# The egress proxy contract (#845), independent of kubeconform: the rendered
# stream is PARSED and held against the chart's values.yaml deep-merged with
# the case's values file, and against deploy/compose.yml (the image and the
# Envoy config are ONE authority across compose and the chart). See the
# header's (i) for every assertion; <values-file> may be "-" for defaults.
egress_contract() {  # <case-label> <rendered-file> <values-file|->
  local label="$1" file="$2" values="$3" prc=0 report
  report=$(trap - ERR; python3 - "$file" "$values" "$CHART_DIR/values.yaml" "$ROOT/deploy/compose.yml" "$PLACEHOLDER" <<'PY'
import copy
import sys

import yaml

COMPONENT = "app.kubernetes.io/component"
# #862 — the six env names the renderer reads; the init container carries ONLY
# these: each of the first five as extraEnv's value when set there, else as an
# optional key reference into existingSecret (the app's own precedence, review R1);
# the max-age is the chart's own value.
RENDER_NAMES = [
    "DATAPIPELINES_LAKE_ENABLED",
    "DATAPIPELINES_LAKE_S3_BUCKET",
    "DATAPIPELINES_LAKE_S3_ENDPOINT",
    "DATAPIPELINES_LAKE_S3_REGION",
    "DATAPIPELINES_LAKE_S3_PATH_STYLE",
]
MAX_AGE_NAME = "DATAPIPELINES_LAKE_READS_EGRESS_MAX_AGE_SECONDS"
# The renderer entrypoint, compose side AND chart side (the parity the one-config
# rule now lives in): one main, one jar, one launcher, one output path.
RENDERER_COMMAND = [
    "java",
    "-Dloader.main=co.datapipelines.web.config.EgressProxyConfigMain",
    "-cp",
    "/app/datapipelines.jar",
    "org.springframework.boot.loader.launch.PropertiesLauncher",
    "/dp-egress-config/envoy.yaml",
]


def merge(base, over):
    out = copy.deepcopy(base)
    for k, v in (over or {}).items():
        out[k] = merge(out[k], v) if isinstance(v, dict) and isinstance(out.get(k), dict) else v
    return out


def main():
    render, values_path, chart_values, compose_path, secret_name = sys.argv[1:6]
    with open(render, encoding="utf-8") as f:
        docs = [d for d in yaml.safe_load_all(f) if d is not None]
    with open(chart_values, encoding="utf-8") as f:
        values = yaml.safe_load(f) or {}
    if values_path != "-":
        with open(values_path, encoding="utf-8") as f:
            values = merge(values, yaml.safe_load(f) or {})
    with open(compose_path, encoding="utf-8") as f:
        compose = yaml.safe_load(f)
    ep = values["lake"]["egressProxy"]
    extra = values.get("extraEnv") or {}

    def labels(d):
        return (d.get("metadata") or {}).get("labels") or {}

    proxy = [d for d in docs if labels(d).get(COMPONENT) == "egress-proxy"]
    apps = [d for d in docs if d.get("kind") == "Deployment" and labels(d).get(COMPONENT) != "egress-proxy"]
    if len(apps) != 1:
        print(f"{len(apps)} app Deployment(s) (expected 1)")
        sys.exit(1)
    app = apps[0]
    app_env = [e for c in app["spec"]["template"]["spec"]["containers"] for e in c.get("env") or []]
    egress_env = [e for e in app_env if e.get("name", "").startswith("DATAPIPELINES_LAKE_READS_EGRESS_")]

    if ep.get("enabled") is not True:
        problems = []
        if proxy:
            problems.append(f"the proxy is off but {[(d['kind'], d['metadata']['name']) for d in proxy]} rendered")
        expected_env = [{"name": k, "value": str(v)} for k, v in sorted(extra.items())
                        if k.startswith("DATAPIPELINES_LAKE_READS_EGRESS_")]
        if egress_env != expected_env:
            problems.append(f"egress env entries {egress_env} (expected only extraEnv's {expected_env})")
        if problems:
            print("; ".join(problems))
            sys.exit(1)
        print(f"proxy off: no proxy object; egress env entries = extraEnv's ({len(expected_env)})")
        return

    problems = []
    kinds = sorted(d["kind"] for d in proxy)
    want = sorted(["Deployment", "Service"]
                  + (["NetworkPolicy"] if ep["networkPolicy"]["enabled"] is True else [])
                  + (["PodDisruptionBudget"] if (ep.get("podDisruptionBudget") or {}).get("enabled") is True else []))
    if kinds != want:
        print(f"proxy objects {kinds} (expected exactly {want} — the #862 config is rendered in-pod, no ConfigMap)")
        sys.exit(1)
    by_kind = {d["kind"]: d for d in proxy}
    dep, svc = by_kind["Deployment"], by_kind["Service"]
    proxy_sel = dep["spec"]["selector"]["matchLabels"]
    app_sel = app["spec"]["selector"]["matchLabels"]
    proxy_pod = dep["spec"]["template"]["metadata"]["labels"]
    app_pod = app["spec"]["template"]["metadata"]["labels"]

    def selects(selector, pod):
        return all(pod.get(k) == v for k, v in selector.items())

    if not selects(proxy_sel, proxy_pod):
        problems.append("the proxy Deployment's selector does not select its own pods")
    if selects(app_sel, proxy_pod):
        problems.append(f"the app selector {app_sel} also selects the proxy pods {proxy_pod}")
    if selects(proxy_sel, app_pod):
        problems.append(f"the proxy selector {proxy_sel} also selects the app pods {app_pod}")

    compose_svc = compose["services"]["egress-proxy"]
    if compose_svc.get("cap_drop") != ["ALL"] or "no-new-privileges:true" not in (compose_svc.get("security_opt") or []):
        problems.append(f"deploy/compose.yml's egress-proxy cap_drop={compose_svc.get('cap_drop')} security_opt={compose_svc.get('security_opt')} (expected [ALL] and no-new-privileges:true)")
    if compose_svc.get("read_only") is not True:
        problems.append("deploy/compose.yml's egress-proxy is not read_only — since #862 it mounts only the renderer's volume, so the writable rootfs accommodation is gone")
    deps = compose_svc.get("depends_on") or {}
    if deps.get("egress-config", {}).get("condition") != "service_completed_successfully":
        problems.append(f"deploy/compose.yml's egress-proxy depends_on {deps} (expected the renderer gated on service_completed_successfully)")

    pod_spec = dep["spec"]["template"]["spec"]
    containers = pod_spec["containers"]
    if len(containers) != 1:
        problems.append(f"{len(containers)} proxy containers (expected 1)")
    c = containers[0]
    ports = {p.get("name"): p.get("containerPort") for p in c.get("ports") or []}
    port = ports.get("proxy")
    if port is None or len(ports) != 1:
        problems.append(f"the proxy containerPorts {ports} (expected exactly one named proxy)")
    if c.get("image") != compose_svc["image"]:
        problems.append(f"the proxy image {c.get('image')} != deploy/compose.yml's {compose_svc['image']}")
    sc = c.get("securityContext") or {}
    if not (sc.get("readOnlyRootFilesystem") is True and sc.get("allowPrivilegeEscalation") is False
            and (sc.get("capabilities") or {}).get("drop") == ["ALL"]):
        problems.append(f"the Envoy container's securityContext {sc} is not read-only/no-escalation/drop-ALL")
    psc = pod_spec.get("securityContext") or {}
    if not (psc.get("runAsNonRoot") is True and psc.get("fsGroup") == 65532):
        problems.append(f"the proxy pod securityContext {psc} is not non-root with fsGroup (the init container's emptyDir needs the group)")

    annotations = dep["spec"]["template"]["metadata"].get("annotations") or {}
    if "checksum/egress-render-inputs" not in annotations:
        problems.append("the proxy pods carry no checksum/egress-render-inputs annotation (a renderer-input change would not roll them)")
    if "checksum/config" in annotations:
        problems.append("the proxy pods still carry the #845 checksum/config annotation")

    # #863 — availability: replicas render from the validated value; the readiness
    # probe (the boot check's twin, lake.boot.egress_proxy) is retained whatever
    # the availability settings; a PDB/spread, when enabled, is a policy/v1
    # object whose selectors are the proxy Deployment's own — never the app's —
    # and availability never leaks onto the app Deployment.
    if dep["spec"].get("replicas") != ep.get("replicaCount"):
        problems.append(f"the proxy Deployment renders replicas={dep['spec'].get('replicas')} "
                        f"(expected the values' replicaCount {ep.get('replicaCount')})")
    http_get = (c.get("readinessProbe") or {}).get("httpGet") or {}
    probe_headers = {h.get("name"): h.get("value") for h in http_get.get("httpHeaders") or []}
    if http_get.get("path") != "/dp-egress" or http_get.get("port") != "proxy" or probe_headers.get("x-dp-egress-check") != "1":
        problems.append(f"the proxy readinessProbe {http_get} headers {probe_headers} is not the header-gated GET /dp-egress on the proxy port")
    app_tsc = app["spec"]["template"]["spec"].get("topologySpreadConstraints")
    if app_tsc:
        problems.append(f"the app Deployment carries topologySpreadConstraints {app_tsc} (availability is the proxy's only)")

    pdb = by_kind.get("PodDisruptionBudget")
    pdb_values = ep.get("podDisruptionBudget") or {}
    if pdb_values.get("enabled") is True:
        if pdb is None:
            problems.append("podDisruptionBudget.enabled but no proxy PodDisruptionBudget rendered")
        else:
            if pdb.get("apiVersion") != "policy/v1":
                problems.append(f"the proxy PDB apiVersion {pdb.get('apiVersion')} (expected policy/v1)")
            if pdb["spec"].get("minAvailable") != pdb_values.get("minAvailable"):
                problems.append(f"the proxy PDB minAvailable {pdb['spec'].get('minAvailable')} "
                                f"(expected the values' {pdb_values.get('minAvailable')})")
            pdb_sel = (pdb["spec"].get("selector") or {}).get("matchLabels")
            if pdb_sel != proxy_sel or not selects(pdb_sel or {}, proxy_pod):
                problems.append(f"the proxy PDB selector {pdb_sel} is not the proxy Deployment's selector {proxy_sel}")
            if selects(pdb_sel or {}, app_pod):
                problems.append(f"the proxy PDB selector {pdb_sel} also selects the app pods {app_pod}")
    elif pdb is not None:
        problems.append("podDisruptionBudget disabled but a proxy PodDisruptionBudget rendered")

    tsc = pod_spec.get("topologySpreadConstraints") or []
    spread_values = ep.get("topologySpreadConstraints") or {}
    if spread_values.get("enabled") is True:
        if len(tsc) != 1:
            problems.append(f"{len(tsc)} topologySpreadConstraints (expected exactly 1)")
        else:
            t = tsc[0]
            if t.get("maxSkew") != spread_values.get("maxSkew"):
                problems.append(f"the spread maxSkew {t.get('maxSkew')} (expected the values' {spread_values.get('maxSkew')})")
            if t.get("topologyKey") != spread_values.get("topologyKey"):
                problems.append(f"the spread topologyKey {t.get('topologyKey')} (expected the values' {spread_values.get('topologyKey')})")
            if t.get("whenUnsatisfiable") != spread_values.get("whenUnsatisfiable"):
                problems.append(f"the spread whenUnsatisfiable {t.get('whenUnsatisfiable')} (expected the values' {spread_values.get('whenUnsatisfiable')})")
            spread_sel = (t.get("labelSelector") or {}).get("matchLabels")
            if spread_sel != proxy_sel:
                problems.append(f"the spread constraint's labelSelector {spread_sel} is not the proxy Deployment's selector {proxy_sel} "
                                "(never an operator-supplied selector — it would be free to select app pods)")
    elif tsc:
        problems.append(f"the spread is disabled but topologySpreadConstraints {tsc} rendered")

    # The init container IS the config now: the app image's renderer main, fed the
    # same inputs the app container carries, writing the volume Envoy reads.
    inits = pod_spec.get("initContainers") or []
    if len(inits) != 1:
        problems.append(f"{len(inits)} init container(s) (expected exactly the renderer)")
    else:
        init = inits[0]
        if init.get("command") != RENDERER_COMMAND:
            problems.append(f"the init container's command {init.get('command')} is not the renderer entrypoint {RENDERER_COMMAND}")
        render_svc = compose["services"]["egress-config"]
        if render_svc.get("entrypoint") != RENDERER_COMMAND:
            problems.append(f"deploy/compose.yml's egress-config entrypoint {render_svc.get('entrypoint')} != the init container's command (one-config rule)")
        if init.get("image") != render_svc.get("image"):
            problems.append(f"the init image {init.get('image')} != deploy/compose.yml's egress-config image {render_svc.get('image')}")
        isb = init.get("securityContext") or {}
        if not (isb.get("readOnlyRootFilesystem") is True and isb.get("allowPrivilegeEscalation") is False
                and (isb.get("capabilities") or {}).get("drop") == ["ALL"]):
            problems.append(f"the init container's securityContext {isb} is not read-only/no-escalation/drop-ALL")
        init_entries = init.get("env") or []
        init_env = {e["name"]: e for e in init_entries}
        max_age = ep["maxAgeSeconds"]
        if init_env.get(MAX_AGE_NAME) != {"name": MAX_AGE_NAME, "value": str(max_age)}:
            problems.append(f"the init container's {MAX_AGE_NAME} entry {init_env.get(MAX_AGE_NAME)} != the chart's maxAgeSeconds {max_age}")
        if len(init_entries) != len(init_env):
            problems.append(f"the init container names an env entry twice ({[e['name'] for e in init_entries]})")
        if init.get("envFrom"):
            problems.append(f"the init container has envFrom {init.get('envFrom')} — never the whole Secret, five named keys only")
        for name in RENDER_NAMES:
            want = ({"name": name, "value": str(extra[name])} if name in extra else
                    {"name": name, "valueFrom": {"secretKeyRef": {"name": secret_name, "key": name, "optional": True}}})
            if init_env.get(name) != want:
                problems.append(f"the init container's {name} entry {init_env.get(name)} != {want} (extraEnv wins, else "
                                f"the Secret's same-named key, optional — the app container's precedence)")
        stray = [k for k in init_env if k != MAX_AGE_NAME and k not in RENDER_NAMES]
        if stray:
            problems.append(f"the init container carries non-renderer env {stray} (least privilege: only the six inputs)")
        mounts = {m["name"]: m for m in init.get("volumeMounts") or []}
        if mounts.get("envoy-config", {}).get("mountPath") != "/dp-egress-config":
            problems.append(f"the init container mounts {mounts} (expected envoy-config at /dp-egress-config)")

    vols = {v["name"]: v for v in pod_spec.get("volumes") or []}
    if list(vols) != ["envoy-config"] or "emptyDir" not in vols.get("envoy-config", {}):
        problems.append(f"the proxy pod volumes {vols} (expected exactly one emptyDir envoy-config)")
    else:
        mounts = {m["name"]: m for m in c.get("volumeMounts") or []}
        if (mounts.get("envoy-config", {}).get("mountPath") != "/etc/envoy"
                or mounts.get("envoy-config", {}).get("readOnly") is not True):
            problems.append(f"the renderer's volume is not mounted read-only at /etc/envoy (mounts {list(mounts)})")

    if svc["spec"].get("selector") != proxy_sel:
        problems.append(f"the proxy Service selector {svc['spec'].get('selector')} != the Deployment's {proxy_sel}")
    sports = svc["spec"].get("ports") or []
    if len(sports) != 1 or sports[0].get("targetPort") != "proxy" or sports[0].get("port") != port:
        problems.append(f"the proxy Service ports {sports} (expected one: port {port} → targetPort proxy)")

    max_age = ep["maxAgeSeconds"]
    service_addr = f"{svc['metadata']['name']}:{port}"
    expected_env = [{"name": "DATAPIPELINES_LAKE_READS_EGRESS_PROXY", "value": service_addr},
                    {"name": MAX_AGE_NAME, "value": str(max_age)}]
    stripped = [{"name": e.get("name"), "value": e.get("value")} for e in egress_env]
    if stripped != expected_env:
        problems.append(f"the app's egress env entries {stripped} (expected {expected_env})")

    if "NetworkPolicy" in by_kind:
        spec = by_kind["NetworkPolicy"]["spec"]
        npv = ep["networkPolicy"]
        store = npv.get("storeEgress") or {}
        if spec.get("podSelector") != {"matchLabels": proxy_sel}:
            problems.append(f"the proxy policy's podSelector {spec.get('podSelector')} is not the proxy Deployment's selector")
        want_ingress = [{"from": [{"podSelector": {"matchLabels": app_sel}}], "ports": [{"protocol": "TCP", "port": port}]}]
        if spec.get("ingress") != want_ingress:
            problems.append(f"the proxy policy's ingress {spec.get('ingress')} (expected only the app pods on TCP {port})")
        if store.get("to") or store.get("ports"):
            rule = {}
            if store.get("to"):
                rule["to"] = store["to"]
            if store.get("ports"):
                rule["ports"] = store["ports"]
            dns = {"ports": [{"protocol": "UDP", "port": 53}, {"protocol": "TCP", "port": 53}]}
            if npv.get("dnsTo"):
                dns = {"to": npv["dnsTo"], **dns}
            if spec.get("policyTypes") != ["Ingress", "Egress"] or spec.get("egress") != [rule, dns]:
                problems.append(f"policyTypes {spec.get('policyTypes')}, egress {spec.get('egress')} (expected [Ingress, Egress] and [{rule}, {dns}])")
        elif spec.get("policyTypes") != ["Ingress"] or "egress" in spec:
            problems.append(f"no store declared, yet policyTypes {spec.get('policyTypes')} / egress {spec.get('egress')} (expected [Ingress] and none)")

    if problems:
        print("; ".join(problems))
        sys.exit(1)
    np_mode = "no policy" if "NetworkPolicy" not in by_kind else (
        "ingress app→proxy + store/DNS egress" if (ep["networkPolicy"].get("storeEgress") or {}).get("to")
        or (ep["networkPolicy"].get("storeEgress") or {}).get("ports") else "ingress app→proxy, egress the cluster's")
    print(f"proxy {svc['metadata']['name']}:{port}, max-age {max_age} in the app env and the renderer init (== compose's entrypoint), image == compose's, {np_mode}")


# An uncaught exception would exit 1 — a VERDICT code; a crash is tooling (2).
try:
    main()
except SystemExit:
    raise
except Exception as e:  # noqa: BLE001
    print(f"crashed: {e!r}")
    sys.exit(2)
PY
) || prc=$?
  case "$prc" in
    0) verdict "$label egress proxy contract" 0 "$report" ;;
    1) verdict "$label egress proxy contract" 1 "$report" ;;
    *) echo "helm-check: the egress proxy contract crashed for $label (exit $prc) — $report (tooling, not a verdict)" >&2; exit 2 ;;
  esac
}

# egress_render <label> <values-file|-> <out> <err> — one successful render:
# exit 0, then the parsed egress contract and the kubeconform leg.
egress_render() {
  local label="$1" values="$2" out="$3" err="$4" rc=0
  local -a extra=()
  [ "$values" = "-" ] || extra=(-f "$values")
  "$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
    "${extra[@]}" >"$out" 2>"$err" || rc=$?
  if [ "$rc" -ne 0 ]; then
    verdict "$label renders" 1 "helm template exited $rc (expected 0)"
    return 0
  fi
  egress_contract "$label" "$out" "$values"
  kc_validate "$label" "$out"
}

# (i) defaults — the proxy ON, its policy admitting only the app pods.
egress_render "(i) egress proxy defaults" - "$WORK/i.out" "$WORK/i.err"

# (i2) the store declared (an ipBlock and TCP 443) at another max-age.
cat >"$WORK/i2.values.yaml" <<'VALUES'
lake:
  egressProxy:
    maxAgeSeconds: 1200
    networkPolicy:
      storeEgress:
        to:
          - ipBlock:
              cidr: 10.20.0.0/16
        ports:
          - protocol: TCP
            port: 443
VALUES
egress_render "(i2) store egress declared" "$WORK/i2.values.yaml" "$WORK/i2.out" "$WORK/i2.err"

# (i3) ports only, and DNS limited to the cluster DNS namespace.
cat >"$WORK/i3.values.yaml" <<'VALUES'
lake:
  egressProxy:
    networkPolicy:
      storeEgress:
        ports:
          - protocol: TCP
            port: 9000
      dnsTo:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: kube-system
VALUES
egress_render "(i3) ports-only store egress" "$WORK/i3.values.yaml" "$WORK/i3.out" "$WORK/i3.err"

# (i4) the proxy's policy off — the proxy objects without it.
cat >"$WORK/i4.values.yaml" <<'VALUES'
lake:
  egressProxy:
    networkPolicy:
      enabled: false
VALUES
egress_render "(i4) proxy policy off" "$WORK/i4.values.yaml" "$WORK/i4.out" "$WORK/i4.err"

# (i5) the proxy off — nothing renders; an operator's own proxy through
# extraEnv is then admitted (the double-mapping refusal holds only while ON).
cat >"$WORK/i5.values.yaml" <<'VALUES'
lake:
  egressProxy:
    enabled: false
extraEnv:
  DATAPIPELINES_LAKE_READS_EGRESS_PROXY: corp-proxy.example:3128
VALUES
egress_render "(i5) proxy off" "$WORK/i5.values.yaml" "$WORK/i5.out" "$WORK/i5.err"

# (j) #863 — availability. The documented two-replica example: a PDB keeps one
# proxy through voluntary disruptions (minAvailable strictly below replicas —
# minAvailable = replicas could never be disrupted and would deadlock every
# drain), and a best-effort spread prefers one pod per node. The contract
# asserts the PDB's and the spread's labelSelector are the PROXY Deployment's
# own selector (never the app's, never operator-supplied) and the readiness
# probe is retained.
cat >"$WORK/j.values.yaml" <<'VALUES'
lake:
  egressProxy:
    replicaCount: 2
    podDisruptionBudget:
      enabled: true
      minAvailable: 1
    topologySpreadConstraints:
      enabled: true
      maxSkew: 1
      whenUnsatisfiable: ScheduleAnyway
      topologyKey: kubernetes.io/hostname
VALUES
egress_render "(j) egress proxy high availability" "$WORK/j.values.yaml" "$WORK/j.out" "$WORK/j.err"

# (j2) the spread alone — a topology constraint with NO disruption budget: the
# spread renders (proxy's own selector), no PDB object does.
cat >"$WORK/j2.values.yaml" <<'VALUES'
lake:
  egressProxy:
    replicaCount: 2
    topologySpreadConstraints:
      enabled: true
VALUES
egress_render "(j2) spread only, no budget" "$WORK/j2.values.yaml" "$WORK/j2.out" "$WORK/j2.err"

# (j3) The Kubernetes fields are signed int32: preserve their valid upper
# boundary without ever asking a cluster to create these replicas.
cat >"$WORK/j3.values.yaml" <<'VALUES'
lake:
  egressProxy:
    replicaCount: 2147483647
    podDisruptionBudget:
      enabled: true
      minAvailable: 2147483646
    topologySpreadConstraints:
      enabled: true
      maxSkew: 2147483647
VALUES
egress_render "(j3) int32 upper bounds" "$WORK/j3.values.yaml" "$WORK/j3.out" "$WORK/j3.err"

# (j4/j5) Validation follows consumption: disabled availability fields, and
# every proxy field when the proxy itself is disabled, remain inert.
cat >"$WORK/j4.values.yaml" <<'VALUES'
lake:
  egressProxy:
    podDisruptionBudget:
      enabled: false
      minAvailable: nonsense
    topologySpreadConstraints:
      enabled: false
      maxSkew: nonsense
      topologyKey: ""
      whenUnsatisfiable: nonsense
VALUES
egress_render "(j4) disabled availability is inert" "$WORK/j4.values.yaml" "$WORK/j4.out" "$WORK/j4.err"
cat >"$WORK/j5.values.yaml" <<'VALUES'
lake:
  egressProxy:
    enabled: false
    replicaCount: nonsense
    podDisruptionBudget:
      enabled: true
      minAvailable: nonsense
    topologySpreadConstraints:
      enabled: true
      maxSkew: nonsense
      topologyKey: ""
      whenUnsatisfiable: nonsense
VALUES
egress_render "(j5) disabled proxy is inert" "$WORK/j5.values.yaml" "$WORK/j5.out" "$WORK/j5.err"

# (i7) #862 review R1 — the renderer's inputs RESOLVED, not just wired. Each case
# renders the chart, then resolves the app container's and the init container's
# environment the way the kubelet does against a FIXTURE Secret (envFrom first,
# then env entries in order, an env entry winning; a secretKeyRef to a missing
# key with optional: true leaves the variable unset), and asserts: (1) the six
# renderer inputs resolve identically in both containers; (2) they equal the
# case's expected values literally (Secret-only, extraEnv override, a partial
# Secret, nothing set, an alias held in the Secret); (3) no other Secret key — the
# fixture carries dummy credentials — reaches the init container. Optional: with
# HELM_CHECK_RENDERER_JAR=<boot jar> the REAL renderer (EgressProxyConfigMain)
# runs on each side's resolved inputs and both outputs must be byte-identical and
# name the expected store authority ("-" = deny-only, "!refused" = both runs must
# exit nonzero). CI sets it in its post-build check; the early check has no jar,
# so its line says "renderer run SKIP" — the resolution itself is still asserted.
egress_resolve() {  # <case-label> <values-file|-> <secret-fixture> <expected-json> <expected-authority|-|!refused>
  local label="$1" values="$2" secret="$3" expected="$4" authority="$5" rc=0 prc=0 report out err
  local -a extra=()
  [ "$values" = "-" ] || extra=(-f "$values")
  out="$WORK/resolve-$(printf '%s' "$label" | tr -c 'A-Za-z0-9' '_').out"
  err="${out%.out}.err"
  "$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" "${extra[@]}" >"$out" 2>"$err" || rc=$?
  if [ "$rc" -ne 0 ]; then
    verdict "$label renders" 1 "helm template exited $rc (expected 0)"
    return 0
  fi
  report=$(trap - ERR; python3 - "$out" "$secret" "$expected" "$authority" "$PLACEHOLDER" "${HELM_CHECK_RENDERER_JAR:-}" "$WORK" <<'PY'
import json
import os
import subprocess
import sys

import yaml

RENDER_INPUTS = [
    "DATAPIPELINES_LAKE_ENABLED",
    "DATAPIPELINES_LAKE_S3_BUCKET",
    "DATAPIPELINES_LAKE_S3_ENDPOINT",
    "DATAPIPELINES_LAKE_S3_REGION",
    "DATAPIPELINES_LAKE_S3_PATH_STYLE",
    "DATAPIPELINES_LAKE_READS_EGRESS_MAX_AGE_SECONDS",
]
RENDERER_MAIN = "co.datapipelines.web.config.EgressProxyConfigMain"
LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher"


def resolve(container, secrets):
    """The kubelet's order: envFrom sources, then env entries (a later entry wins)."""
    env = {}
    for source in container.get("envFrom") or []:
        ref = source.get("secretRef")
        if ref is None:
            raise SystemExit(f"unsupported envFrom source {source}")
        env.update(secrets[ref["name"]])
    for entry in container.get("env") or []:
        name = entry["name"]
        if "value" in entry:
            env[name] = str(entry["value"])
        elif "valueFrom" in entry:
            ref = entry["valueFrom"].get("secretKeyRef")
            if ref is None:
                raise SystemExit(f"unsupported valueFrom {entry}")
            data = secrets.get(ref["name"])
            if data is None or ref["key"] not in data:
                if not ref.get("optional"):
                    raise SystemExit(f"{name}: a required key reference to a missing key — the pod would not start")
                env.pop(name, None)
            else:
                env[name] = data[ref["key"]]
        else:
            env[name] = ""
    return env


def run_renderer(jar, env, out_path):
    base = {"PATH": os.environ.get("PATH", "/usr/bin:/bin")}
    proc = subprocess.run(
        ["java", f"-Dloader.main={RENDERER_MAIN}", "-cp", jar, LAUNCHER, out_path],
        env={**base, **env}, capture_output=True, text=True, timeout=120, check=False,
    )
    if proc.returncode != 0:
        return proc.returncode, proc.stderr.strip()[:300]
    with open(out_path, encoding="utf-8") as f:
        return 0, f.read()


def main():
    render, secret_path, expected_json, authority, secret_name, jar, work = sys.argv[1:8]
    with open(render, encoding="utf-8") as f:
        docs = [d for d in yaml.safe_load_all(f) if d is not None]
    with open(secret_path, encoding="utf-8") as f:
        fixture = yaml.safe_load(f) or {}
    secrets = {secret_name: {str(k): str(v) for k, v in fixture.items()}}
    expected = json.loads(expected_json)

    def component(d):
        return ((d.get("metadata") or {}).get("labels") or {}).get("app.kubernetes.io/component")

    deployments = [d for d in docs if d.get("kind") == "Deployment"]
    apps = [d for d in deployments if component(d) != "egress-proxy"]
    proxies = [d for d in deployments if component(d) == "egress-proxy"]
    if len(apps) != 1 or len(proxies) != 1:
        print(f"{len(apps)} app / {len(proxies)} proxy Deployment(s) (expected 1/1)")
        sys.exit(1)
    app_containers = apps[0]["spec"]["template"]["spec"]["containers"]
    inits = proxies[0]["spec"]["template"]["spec"].get("initContainers") or []
    if len(app_containers) != 1 or len(inits) != 1:
        print(f"{len(app_containers)} app container(s) / {len(inits)} init container(s) (expected 1/1)")
        sys.exit(1)
    app_env = resolve(app_containers[0], secrets)
    init_env = resolve(inits[0], secrets)

    problems = []
    app_inputs = {k: app_env[k] for k in RENDER_INPUTS if k in app_env}
    init_inputs = {k: init_env[k] for k in RENDER_INPUTS if k in init_env}
    if app_inputs != init_inputs:
        problems.append(f"the renderer inputs resolve differently: app {app_inputs} vs init {init_inputs}")
    if init_inputs != expected:
        problems.append(f"the init container resolves {init_inputs}, expected {expected}")
    leaked = sorted(k for k in init_env if k not in RENDER_INPUTS)
    if leaked:
        problems.append(f"the init container resolves non-renderer variables {leaked} (least privilege)")
    if problems:
        print("; ".join(problems))
        sys.exit(1)

    unset = [k for k in RENDER_INPUTS if k not in init_inputs]
    summary = (f"{len(init_inputs)} input(s) resolve identically in app and init ({', '.join(sorted(init_inputs))}); "
               f"unset in both: {len(unset)}; init sees {len(init_env)} variable(s), no other Secret key")
    if not jar:
        print(summary + "; renderer run SKIP (HELM_CHECK_RENDERER_JAR unset)")
        return
    (lrc, left), (rrc, right) = (run_renderer(jar, init_inputs, os.path.join(work, "resolve-init-envoy.yaml")),
                                 run_renderer(jar, app_inputs, os.path.join(work, "resolve-app-envoy.yaml")))
    if authority == "!refused":
        if lrc == 0 or rrc == 0:
            print(summary + f"; the renderer was expected to refuse these inputs (exit {lrc}/{rrc})")
            sys.exit(1)
        print(summary + f"; the real renderer on both sides refuses, fail-closed: {left}")
        return
    if lrc != 0 or rrc != 0 or left != right:
        print(summary + f"; the renderer's output differs between the two resolved environments (exit {lrc}/{rrc})")
        sys.exit(1)
    config = json.loads(left)
    vhosts = config["static_resources"]["listeners"][0]["filter_chains"][0]["filters"][0]["typed_config"]["route_config"]["virtual_hosts"]
    domains = [d for vh in vhosts for d in vh["domains"] if d != "*"]
    want = [] if authority == "-" else [authority]
    if domains != want:
        print(summary + f"; the renderer allowlists {domains}, expected {want}")
        sys.exit(1)
    print(summary + f"; the real renderer on both sides: byte-identical, allowlist {domains or 'deny-only'}")


try:
    main()
except SystemExit:
    raise
except Exception as e:  # noqa: BLE001
    print(f"crashed: {e!r}")
    sys.exit(2)
PY
) || prc=$?
  case "$prc" in
    0) verdict "$label renderer inputs resolved" 0 "$report" ;;
    1) verdict "$label renderer inputs resolved" 1 "$report" ;;
    *) echo "helm-check: the renderer-input resolution crashed for $label (exit $prc) — $report (tooling, not a verdict)" >&2; exit 2 ;;
  esac
}

# The fixture Secret's credentials are dummy local values; they exist so a leak
# into the init container would be visible.
cat >"$WORK/r-secret-only.secret.yaml" <<'SECRET'
DATAPIPELINES_JWT_SECRET: dummy-local-jwt-not-a-secret
DATAPIPELINES_LAKE_S3_ACCESS_KEY_ID: dummy-local-access-key
DATAPIPELINES_LAKE_S3_SECRET_ACCESS_KEY: dummy-local-secret-key
DATAPIPELINES_LAKE_ENABLED: "true"
DATAPIPELINES_LAKE_S3_BUCKET: dp-lake
DATAPIPELINES_LAKE_S3_ENDPOINT: http://minio.store.svc:9000
DATAPIPELINES_LAKE_S3_PATH_STYLE: "true"
SECRET
egress_resolve "(i7a) lake values in the Secret only" - "$WORK/r-secret-only.secret.yaml" \
  '{"DATAPIPELINES_LAKE_ENABLED": "true", "DATAPIPELINES_LAKE_S3_BUCKET": "dp-lake", "DATAPIPELINES_LAKE_S3_ENDPOINT": "http://minio.store.svc:9000", "DATAPIPELINES_LAKE_S3_PATH_STYLE": "true", "DATAPIPELINES_LAKE_READS_EGRESS_MAX_AGE_SECONDS": "960"}' \
  "minio.store.svc:9000"

cat >"$WORK/r-override.values.yaml" <<'VALUES'
extraEnv:
  DATAPIPELINES_LAKE_S3_BUCKET: dp-lake-override
  DATAPIPELINES_LAKE_S3_ENDPOINT: https://store.example.test
  DATAPIPELINES_LAKE_S3_PATH_STYLE: "false"
VALUES
egress_resolve "(i7b) extraEnv overrides the Secret" "$WORK/r-override.values.yaml" "$WORK/r-secret-only.secret.yaml" \
  '{"DATAPIPELINES_LAKE_ENABLED": "true", "DATAPIPELINES_LAKE_S3_BUCKET": "dp-lake-override", "DATAPIPELINES_LAKE_S3_ENDPOINT": "https://store.example.test", "DATAPIPELINES_LAKE_S3_PATH_STYLE": "false", "DATAPIPELINES_LAKE_READS_EGRESS_MAX_AGE_SECONDS": "960"}' \
  "dp-lake-override.store.example.test:443"

cat >"$WORK/r-partial.secret.yaml" <<'SECRET'
DATAPIPELINES_JWT_SECRET: dummy-local-jwt-not-a-secret
DATAPIPELINES_LAKE_S3_REGION: eu-west-1
SECRET
cat >"$WORK/r-partial.values.yaml" <<'VALUES'
extraEnv:
  DATAPIPELINES_LAKE_ENABLED: "true"
  DATAPIPELINES_LAKE_S3_BUCKET: dp-lake
VALUES
egress_resolve "(i7c) partial Secret + extraEnv, AWS regional" "$WORK/r-partial.values.yaml" "$WORK/r-partial.secret.yaml" \
  '{"DATAPIPELINES_LAKE_ENABLED": "true", "DATAPIPELINES_LAKE_S3_BUCKET": "dp-lake", "DATAPIPELINES_LAKE_S3_REGION": "eu-west-1", "DATAPIPELINES_LAKE_READS_EGRESS_MAX_AGE_SECONDS": "960"}' \
  "dp-lake.s3.eu-west-1.amazonaws.com:443"

cat >"$WORK/r-none.secret.yaml" <<'SECRET'
DATAPIPELINES_JWT_SECRET: dummy-local-jwt-not-a-secret
SECRET
egress_resolve "(i7d) no lake value anywhere" - "$WORK/r-none.secret.yaml" \
  '{"DATAPIPELINES_LAKE_READS_EGRESS_MAX_AGE_SECONDS": "960"}' "-"

# An alias held in the Secret binds in the app only (Spring relaxed binding); the
# renderer never sees it — the app's boot check then meets the proxy's own
# refusal and refuses (lake.boot.egress_proxy, deployment.md §4.3).
cat >"$WORK/r-alias.secret.yaml" <<'SECRET'
DATAPIPELINES_LAKE_ENABLED: "true"
DATAPIPELINES_LAKE_STORAGE_BUCKET: dp-lake
SECRET
egress_resolve "(i7e) a relaxed-binding alias in the Secret reaches only the app" - "$WORK/r-alias.secret.yaml" \
  '{"DATAPIPELINES_LAKE_ENABLED": "true", "DATAPIPELINES_LAKE_READS_EGRESS_MAX_AGE_SECONDS": "960"}' "!refused"

# (i6) refusals — each with the template's own message verbatim.
check_double_mapping "(i6) extraEnv double-maps DATAPIPELINES_LAKE_READS_EGRESS_PROXY" \
  DATAPIPELINES_LAKE_READS_EGRESS_PROXY other:3128 \
  'lake.egressProxy.enabled sets DATAPIPELINES_LAKE_READS_EGRESS_PROXY — not extraEnv (deployment.md §6.4)' \
  "$WORK/i6a.out" "$WORK/i6a.err"
check_double_mapping "(i6) extraEnv double-maps DATAPIPELINES_LAKE_READS_EGRESS_MAX_AGE_SECONDS" \
  DATAPIPELINES_LAKE_READS_EGRESS_MAX_AGE_SECONDS 1200 \
  'set lake.egressProxy.maxAgeSeconds, not extraEnv.DATAPIPELINES_LAKE_READS_EGRESS_MAX_AGE_SECONDS (deployment.md §6.4)' \
  "$WORK/i6b.out" "$WORK/i6b.err"
# (i6, #862) the four relaxed-binding lake-storage aliases: Spring binds them to
# the app's lake.storage.* properties, but the renderer reads only the exact env
# names — an operator setting an alias would get a store the app sees and the
# proxy's allowlist does not. Each refused with the template's own message.
check_double_mapping "(i6) extraEnv double-maps the storage bucket alias" \
  DATAPIPELINES_LAKE_STORAGE_BUCKET dp-lake \
  'set extraEnv.DATAPIPELINES_LAKE_S3_BUCKET, not the relaxed-binding alias DATAPIPELINES_LAKE_STORAGE_BUCKET — the proxy renderer reads only the exact env names (deployment.md §4.3)' \
  "$WORK/i6c.out" "$WORK/i6c.err"
check_double_mapping "(i6) extraEnv double-maps the storage region alias" \
  DATAPIPELINES_LAKE_STORAGE_REGION us-east-1 \
  'set extraEnv.DATAPIPELINES_LAKE_S3_REGION, not the relaxed-binding alias DATAPIPELINES_LAKE_STORAGE_REGION — the proxy renderer reads only the exact env names (deployment.md §4.3)' \
  "$WORK/i6d.out" "$WORK/i6d.err"
check_double_mapping "(i6) extraEnv double-maps the storage endpoint alias" \
  DATAPIPELINES_LAKE_STORAGE_ENDPOINT http://minio:9000 \
  'set extraEnv.DATAPIPELINES_LAKE_S3_ENDPOINT, not the relaxed-binding alias DATAPIPELINES_LAKE_STORAGE_ENDPOINT — the proxy renderer reads only the exact env names (deployment.md §4.3)' \
  "$WORK/i6e.out" "$WORK/i6e.err"
check_double_mapping "(i6) extraEnv double-maps the storage path-style alias" \
  DATAPIPELINES_LAKE_STORAGE_PATH_STYLE true \
  'set extraEnv.DATAPIPELINES_LAKE_S3_PATH_STYLE, not the relaxed-binding alias DATAPIPELINES_LAKE_STORAGE_PATH_STYLE — the proxy renderer reads only the exact env names (deployment.md §4.3)' \
  "$WORK/i6f.out" "$WORK/i6f.err"
np_refusal "(i6) maxAgeSeconds 0 refused" \
  'lake.egressProxy.maxAgeSeconds must be a whole number of seconds greater than 0 (deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    maxAgeSeconds: 0
VALUES
np_refusal "(i6) fractional maxAgeSeconds refused" \
  'lake.egressProxy.maxAgeSeconds must be a whole number of seconds greater than 0 (deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    maxAgeSeconds: 960.5
VALUES
np_refusal "(i6) quoted maxAgeSeconds refused" \
  'lake.egressProxy.maxAgeSeconds must be a whole number of seconds, unquoted (deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    maxAgeSeconds: "960"
VALUES
np_refusal "(i6) tag-only proxy image refused" \
  'lake.egressProxy.image.digest must be a sha256: digest — the proxy image is pinned by digest (deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    image:
      digest: ""
VALUES
np_refusal "(i6) bare label map in a store peer refused" \
  'lake.egressProxy.networkPolicy.storeEgress.to[0].namespaceSelector must be a LabelSelector object — matchLabels / matchExpressions — not a bare label map (found key "kubernetes.io/metadata.name"; deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    networkPolicy:
      storeEgress:
        to:
          - namespaceSelector:
              kubernetes.io/metadata.name: storage
VALUES
np_refusal "(i6) unknown key in a DNS peer refused" \
  'lake.egressProxy.networkPolicy.dnsTo[0] carries "namespace" — a NetworkPolicyPeer has only namespaceSelector / podSelector / ipBlock (deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    networkPolicy:
      dnsTo:
        - namespace: kube-system
VALUES
np_refusal "(i6) non-list store peers refused" \
  'lake.egressProxy.networkPolicy.storeEgress.to must be a list of NetworkPolicyPeer objects (deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    networkPolicy:
      storeEgress:
        to:
          ipBlock:
            cidr: 10.20.0.0/16
VALUES

# (j-r) #863 — the availability refusals, each with the template's own message
# verbatim (np_refusal is generic: values on stdin, exact-message grep).
np_refusal "(j-r) replicaCount 0 refused" \
  'lake.egressProxy.replicaCount must be a whole number of replicas greater than 0 (deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    replicaCount: 0
VALUES
np_refusal "(j-r) fractional replicaCount refused" \
  'lake.egressProxy.replicaCount must be a whole number of replicas greater than 0 (deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    replicaCount: 1.5
VALUES
np_refusal "(j-r) int32-overflow replicaCount refused" \
  'lake.egressProxy.replicaCount must not exceed 2147483647 (Kubernetes int32, deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    replicaCount: 2147483648
VALUES
np_refusal "(j-r) quoted replicaCount refused" \
  'lake.egressProxy.replicaCount must be a whole number of replicas, unquoted (deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    replicaCount: "2"
VALUES
np_refusal "(j-r) minAvailable = replicaCount refused (the drain deadlock)" \
  'lake.egressProxy.podDisruptionBudget.minAvailable (2) must be strictly below lake.egressProxy.replicaCount (2) — minAvailable = replicaCount can never be disrupted and deadlocks every voluntary eviction (deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    replicaCount: 2
    podDisruptionBudget:
      enabled: true
      minAvailable: 2
VALUES
np_refusal "(j-r) minAvailable below 1 refused" \
  'lake.egressProxy.podDisruptionBudget.minAvailable must be a whole number at least 1 (deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    podDisruptionBudget:
      enabled: true
      minAvailable: 0
VALUES
np_refusal "(j-r) quoted minAvailable refused" \
  'lake.egressProxy.podDisruptionBudget.minAvailable must be a whole number, unquoted (deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    podDisruptionBudget:
      enabled: true
      minAvailable: "1"
VALUES
np_refusal "(j-r) budget enabled on the one-replica default refused (the single-node deadlock)" \
  'lake.egressProxy.podDisruptionBudget.minAvailable (1) must be strictly below lake.egressProxy.replicaCount (1) — minAvailable = replicaCount can never be disrupted and deadlocks every voluntary eviction (deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    podDisruptionBudget:
      enabled: true
      minAvailable: 1
VALUES
np_refusal "(j-r) maxSkew 0 refused" \
  'lake.egressProxy.topologySpreadConstraints.maxSkew must be a whole number greater than 0 (deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    topologySpreadConstraints:
      enabled: true
      maxSkew: 0
VALUES
np_refusal "(j-r) int32-overflow maxSkew refused" \
  'lake.egressProxy.topologySpreadConstraints.maxSkew must not exceed 2147483647 (Kubernetes int32, deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    topologySpreadConstraints:
      enabled: true
      maxSkew: 2147483648
VALUES
np_refusal "(j-r) unknown whenUnsatisfiable refused" \
  'lake.egressProxy.topologySpreadConstraints.whenUnsatisfiable must be ScheduleAnyway or DoNotSchedule, not "Sometimes" (deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    topologySpreadConstraints:
      enabled: true
      whenUnsatisfiable: Sometimes
VALUES
np_refusal "(j-r) empty topologyKey refused" \
  'lake.egressProxy.topologySpreadConstraints.topologyKey must be a non-empty string, e.g. kubernetes.io/hostname (deployment.md §6.4)' <<'VALUES'
lake:
  egressProxy:
    topologySpreadConstraints:
      enabled: true
      topologyKey: ""
VALUES

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
