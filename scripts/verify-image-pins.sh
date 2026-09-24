#!/usr/bin/env bash
#
# verify-image-pins.sh — every container image the tests pin must still exist where the pin
# says, checked against the REGISTRY, never the local cache.
#
# Why (MISTAKES.md, "Green on the Laptop Because of What the Laptop Has CACHED", twice):
# 2026-09-11 MinIO withdrew a tag from Docker Hub; 2026-09-24 MinIO removed EVERY tag from
# quay.io/minio/minio and its binary downloads (HTTP 410). Both times every local gate was
# green for days because the laptop had the image, and CI's integration job failed on every
# push. A `docker pull` is a cache hit here and a 2-minute Testcontainers timeout there.
#
# What it does: greps the test trees for `registry/repo:tag` literals (the shape Testcontainers
# takes), de-duplicates them, and asks the registry for each manifest with
# `docker manifest inspect`, which never consults the local image store. Exit 1 listing every
# pin the registry no longer serves; exit 0 when every pin resolves. Exit 2 when the grep found
# nothing (a broken grep must never read as "all pins fine" — the non-vacuity floor).
#
# Runs: in CI before the integration tests (a missing pin fails in seconds, not after a
# 2-minute pull timeout per suite), and by hand before a dependency review. Needs the Docker
# CLI and network; no daemon-side image is touched.
#
# Usage: ./scripts/verify-image-pins.sh            (from the repo root)
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"

# registry-qualified or Docker-Hub-official images, with an exact tag; excludes build/ dirs.
pins="$(grep -rhoE '"([a-z0-9.-]+(\.[a-z]{2,})(:[0-9]+)?/)?[a-z0-9._/-]+:[A-Za-z0-9][A-Za-z0-9._-]*"' \
          modules/*/src/test tests/*/src/test 2>/dev/null \
        | tr -d '"' \
        | grep -E '^([a-z0-9.-]+\.[a-z]{2,}(:[0-9]+)?/[a-z0-9._/-]+|(postgres|redis|mysql|mariadb|minio/minio|greenmail/[a-z-]+|gvenzl/[a-z-]+|testcontainers/[a-z-]+))(:[A-Za-z0-9][A-Za-z0-9._-]*)$' \
        | sort -u)"

count=$(printf '%s\n' "$pins" | grep -c . || true)
if [ "$count" -eq 0 ]; then
  echo "verify-image-pins: found NO image pins in modules/*/src/test or tests/*/src/test — the grep is broken, not the tree" >&2
  exit 2
fi
echo "verify-image-pins: $count pinned image(s) to check against their registries"

missing=0
while IFS= read -r image; do
  [ -z "$image" ] && continue
  if out=$(docker manifest inspect "$image" 2>&1 >/dev/null); then
    echo "  ok       $image"
  else
    echo "  MISSING  $image — $(echo "$out" | tr '\n' ' ' | cut -c1-120)"
    missing=$((missing + 1))
  fi
done <<< "$pins"

if [ "$missing" -gt 0 ]; then
  echo "verify-image-pins: $missing pin(s) no longer served by their registry. Move the pin (a mirror the project controls, or a tag that exists) before the next push; every local gate is green only because the image is cached here." >&2
  exit 1
fi
echo "verify-image-pins: every pin resolves at its registry"
