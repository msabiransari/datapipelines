#!/usr/bin/env bash
# helm-check.sh — render the reference Helm chart (deploy/helm/datapipelines)
# with a PINNED helm binary: `helm lint --strict` plus the `helm template`
# cases below — eighty-nine `verdict` assertions in total, labels `(a)`–`(h5h)`
# — whose assertions pin the chart's render-TIME behaviour, and validate every
# successful render against the Kubernetes API schema with a PINNED
# kubeconform (the `kubeconform` leg below; #712) — the Kotlin drift
# tests HelmDuckDbValuesSpecDriftTest, HelmScriptJsValuesSpecDriftTest and
# HelmManagementValuesSpecDriftTest pin only the chart's TEXT (the pregate has
# no helm binary), so the real engine is exercised here (#411, closing the
# text-vs-engine gap #140 left).
#
#   ./scripts/helm-check.sh        # from the repo root; see EXIT CONTRACT
#
# The kubeconform leg: EVERY case that renders successfully — (a), (b), (b2),
# (b2-single), (b2-zero) ×8, (b2-null), (g) ×2, (f), (f-pvc), (h), (h2),
# (h2b), (h2c), (h3) — gets one more verdict, `<case> kubeconform`: the render
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
#         `value: ""`, no `env:` block at all (an empty entry would override the
#         image's own DATAPIPELINES_DUCKDB_EXTENSION_DIRECTORY or
#         DATAPIPELINES_SCRIPT_JS_ISOLATE_DIR — the #140/#688 trap); no /app/lib.
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

    deployments = [d for d in docs if d.get("kind") == "Deployment"]
    policies = [d for d in docs if d.get("kind") == "NetworkPolicy"]
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
if [ "$rc" -eq 0 ]; then kc_validate "(a) defaults" "$WORK/a.out"; fi

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
"$HELM" template dp "$CHART_DIR" --set "existingSecret=$PLACEHOLDER" \
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
n_np=$(grep -c 'kind: NetworkPolicy' "$WORK/h.out" || true)
n_sm=$(grep -c 'ServiceMonitor' "$WORK/h.out" || true)
if [ "$rc" -ne 0 ]; then
  verdict "(h) management defaults" 1 "helm template exited $rc (expected 0)"
elif [ "$n_9090" -ne 0 ] || [ "$n_mgmt_env" -ne 0 ] || [ "$n_np" -ne 0 ] || [ "$n_sm" -ne 0 ]; then
  verdict "(h) management defaults" 1 "9090=$n_9090 MANAGEMENT_=$n_mgmt_env NetworkPolicy=$n_np ServiceMonitor=$n_sm (expected 0 each — the management block is off by default)"
else
  verdict "(h) management defaults" 0 "exit 0; no 9090, no MANAGEMENT_* env entry, no NetworkPolicy, no ServiceMonitor"
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
