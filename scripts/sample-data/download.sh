#!/usr/bin/env bash
# scripts/sample-data/download.sh — stage 1 of the sample-data build
# (design §4.1): fetch every pinned source into work/raw/ and VERIFY it.
#
#   ./scripts/sample-data/download.sh
#
# This script only ever verifies. It cannot re-pin — that is pin.sh, a separate
# deliberate command — because a downloader that repaired its own checksums
# would make D8's "rebuildable artifact" claim unfalsifiable: the first silently
# re-published upstream file would change what we publish and nothing would say so.
#
# Already-present files with the right hash are left alone, so a failed later
# stage re-runs without re-downloading ~1.2 GB. A file whose hash is WRONG is
# deleted by the verifier, so the next run re-fetches it — that is the only
# self-healing here, and it heals a truncated download, never a drifted pin.
#
# Sources whose upstream file is genuinely not immutable are pinned
# `unpinned:<reason>` in sources.lock and skipped here; their determinism is
# enforced by transform.sh against the `window` content pins. See sources.lock.

set -euo pipefail
SD_SCRIPT=download
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
source "$SD_ROOT/lib/common.sh"

RAW="$SD_ROOT/work/raw"
mkdir -p "$RAW"
require_cmd curl "download.sh fetches the pinned sources over HTTPS"

pinned=0 unpinned=0 fetched=0
for id in $(lock_ids url); do
  url=$(lock_field url "$id")
  want=$(lock_field sha256 "$id")
  dest="$RAW/$id"
  [ -n "$want" ] || die "sources.lock declares 'url $id' with no matching sha256 line"

  case "$want" in
    unpinned:*)
      # Re-fetched every run: the point of an unpinned source is that its
      # content moves, so a cached copy would silently freeze the extract.
      log "fetching $id (${want#unpinned:} — content-pinned at transform, not here)"
      fetch "$url" "$dest"
      unpinned=$((unpinned + 1))
      continue
      ;;
  esac

  if [ -f "$dest" ] && [ "$(sha256_of "$dest")" = "$want" ]; then
    pinned=$((pinned + 1))
    continue
  fi
  log "fetching $id"
  fetch "$url" "$dest"
  sha256_expect "$dest" "$want" "$id"
  pinned=$((pinned + 1))
  fetched=$((fetched + 1))
done

[ "$pinned" -gt 0 ] || die "sources.lock declared no pinned sources — nothing was verified, which is not the same as a clean run"

log "OK — $pinned pinned source(s) verified against sources.lock ($fetched newly downloaded), $unpinned unpinned source(s) refreshed"
