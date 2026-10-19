#!/usr/bin/env bash
#
# verify-plotly-bundles.sh — rebuild both vendored Plotly bundles and compare every byte.
#
# Why (#530 research, 2026-10-17; #571 ruling, 2026-10-18): a hand edit or stale rebuild
# of a 1.2/1.8 MB minified file is invisible to review. The manifest pins the bytes but
# cannot prove they came from the recipe. Patch 0003 makes that recipe path-independent.
#
# What it does: clone v4.1.1 into a fresh temporary directory, apply the numbered patches
# in order, npm ci from the tag's integrity-pinned lockfile, build both trace lists from
# docs/deployment.md §6.2A, then cmp each output with the repository's bundle. The trap
# removes the scratch directory, including the private npm cache, on exit.
#
# Verdicts: one line per bundle, ok / DIFFERS with rebuilt and committed SHA-256 hashes.
# Exit 0: both match. Exit 1: rebuild succeeded, at least one byte mismatch.
# Exit 2: could not rebuild or compare (including missing tools, patches or bundles).
# Every run ends with VERIFY_EXIT=<n>; infrastructure failure is never a mismatch.
#
# Runs: the LANDER when a diff touches static/vendor/plotly/ or the manifest's plotly
# entry, and by hand before copying a rebuild in. Never in gate, pregate or CI.
# Needs: bash, git, node, npm, cmp, sha256sum, mktemp and network to github.com (clone)
# and registry.npmjs.org (npm ci). No credentials or customer data leave the box.
# Overrides for exercising red paths without editing the repository:
#   DP_PLOTLY_PATCHES_DIR — directory containing patches (default: vendored patches/).
#   DP_PLOTLY_SOURCE — clone source (default: https://github.com/plotly/plotly.js.git).
# Usage: ./scripts/verify-plotly-bundles.sh (from any directory).
set -euo pipefail

scratch=''
stage='initialization'
finish() {
  local verdict=$?
  trap - EXIT
  if [[ -n "$scratch" ]] && ! rm -rf -- "$scratch"; then
    printf 'rebuild failed: scratch cleanup\n' >&2
    verdict=2
  fi
  printf 'VERIFY_EXIT=%s\n' "$verdict"
  exit "$verdict"
}
failed() {
  printf 'rebuild failed: %s\n' "$stage" >&2
  exit 2
}
trap finish EXIT
trap failed ERR

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
vendor="$REPO/modules/web/src/main/resources/static/vendor/plotly"
patches_dir="${DP_PLOTLY_PATCHES_DIR:-$vendor/patches}"
# Resolve an override before changing to the scratch clone.
patches_dir="$(cd "$patches_dir" && pwd)"
source_url="${DP_PLOTLY_SOURCE:-https://github.com/plotly/plotly.js.git}"
scratch="$(mktemp -d)"
printf 'Scratch: %s\n' "$scratch"
export npm_config_cache="$scratch/npm-cache"

stage='clone'
git clone --depth 1 --branch v4.1.1 "$source_url" "$scratch/plotly.js"
cd "$scratch/plotly.js"
stage='apply patches'
shopt -s nullglob
patches=("$patches_dir"/*.patch)
if (( ${#patches[@]} == 0 )); then
  failed
fi
for patch in "${patches[@]}"; do
  git apply "$patch"
  printf 'Applied %s (exit 0)\n' "${patch##*/}"
done
stage='npm ci'
npm ci --no-audit --no-fund
stage='2D bundle'
npm run custom-bundle -- --traces scatter,bar,pie,histogram,box,heatmap --strict --out 2d
stage='3D bundle'
npm run custom-bundle -- --traces scatter,bar,pie,histogram,box,heatmap,scatter3d,surface,mesh3d --strict --out 3d

verdict=0
stage='compare bundles'
for bundle in plotly-2d.min.js plotly-3d.min.js; do
  rebuilt_hash="$(sha256sum "dist/$bundle")"
  committed_hash="$(sha256sum "$vendor/$bundle")"
  if cmp -s "dist/$bundle" "$vendor/$bundle"; then
    result=ok
  else
    compare_exit=$?
    if [[ "$compare_exit" != 1 ]]; then
      failed
    fi
    result=DIFFERS
    verdict=1
  fi
  printf '%s %s rebuilt=%s committed=%s\n' "$result" "$bundle" "${rebuilt_hash%% *}" "${committed_hash%% *}"
done
exit "$verdict"
