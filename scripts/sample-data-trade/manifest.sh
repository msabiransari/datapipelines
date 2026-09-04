#!/usr/bin/env bash
# scripts/sample-data-trade/manifest.sh — stage 4 of the trade/v1 build
# (mirrors ../sample-data/manifest.sh): assemble manifest.json on STDOUT from
# the artifacts + checksums.tsv + sources.lock params. stdout is the JSON; all
# progress goes to stderr (house convention — a script's stdout stays usable
# as data).
#
#   ./scripts/sample-data-trade/manifest.sh > work/artifacts/manifest.json

set -euo pipefail
SD_SCRIPT=manifest
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
source "$REPO_ROOT/scripts/sample-data/lib/common.sh"

ART="$SD_ROOT/work/artifacts"
CHECKSUMS="$ART/checksums.tsv"
[ -d "$ART" ] || die "work/artifacts/ is missing — run load-and-dump.sh first"
[ -f "$CHECKSUMS" ] || die "work/artifacts/checksums.tsv is missing — run load-and-dump.sh first"

WINDOW_START=$(lock_field param window_start 3)
WINDOW_END=$(lock_field param window_end 3)
ARTIFACT_VERSION=$(lock_field param artifact_version 3)
LICENSE_VERIFIED=$(lock_field param license_verified 3)
[ -n "$ARTIFACT_VERSION" ] || die "sources.lock has no artifact_version param"

python3 - "$ART" "$CHECKSUMS" "$WINDOW_START" "$WINDOW_END" "$ARTIFACT_VERSION" "${LICENSE_VERIFIED:-null}" <<'PY'
import hashlib, json, os, sys

art_dir, checksums_path, w_start, w_end, version, lic = sys.argv[1:7]
lic = None if lic in ("null", "") else lic

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

restore_hints = {
    "us_trade.duckdb": "register as a DUCKDB datasource: jdbc:duckdb:<path> (readonly)",
    "mysql-trade.sql.gz": "gunzip < mysql-trade.sql.gz | mysql (creates database dp_sample_trade)",
    "crypto_market.db": "register as a SQLITE datasource: jdbc:sqlite:<path> (readonly)",
    "examples.json": "seed content: templates + example pipelines for new workspaces",
}

artifacts = []
for name in sorted(os.listdir(art_dir)):
    if name == "manifest.json" or name == "checksums.tsv":
        continue
    p = os.path.join(art_dir, name)
    if not os.path.isfile(p):
        continue
    # Every artifact the manifest may list lives in restore_hints; unknown
    # files are refused (never ship what the manifest cannot name). examples.json
    # has no engine — that is a property of the artifact, not a skip signal
    # (an earlier draft treated engine=None as "skip" and silently dropped
    # examples.json from the manifest — the loader would then never seed the
    # trade examples).
    if name not in restore_hints:
        continue
    engine = {"us_trade.duckdb": "DUCKDB", "mysql-trade.sql.gz": "MYSQL",
              "crypto_market.db": "SQLITE"}.get(name)
    artifacts.append({"file": name, "engine": engine, "sha256": sha256(p),
                      "bytes": os.path.getsize(p),
                      "restore_hint": restore_hints[name]})

table_checksums = []
with open(checksums_path) as f:
    for line in f:
        parts = line.rstrip("\n").split("\t")
        if len(parts) != 4:
            continue
        table_checksums.append({"engine": parts[0], "table": parts[1],
                                "rows": int(parts[2]), "sha256": parts[3]})

provenance = [
    {"source": "US Census Bureau International Trade API (imports/hs, exports/hs)",
     "source_url": "https://api.census.gov/data/timeseries/intltrade/",
     "retrieved_at": None,  # stamped by the owner at publication; a build date here would be a lie about reproducibility
     "transform": "per partner-month API pulls merged to canonical JSONL; HS-6 rows kept as the aggregate grain; lookups derived from the same pulls",
     "artifact": "us_trade.duckdb",
     "license": "US Government work; Census API terms of service",
     "license_verified": lic},
    {"source": "UN Comtrade preview API (annual TOTAL flows, reporters x USA)",
     "source_url": "https://comtradeapi.un.org/public/v1/preview/",
     "retrieved_at": None,
     "transform": "preview pulls per reporter-year-flow merged to canonical JSONL; values cast to DECIMAL",
     "artifact": "mysql-trade.sql.gz",
     "license": "UN Comtrade terms of use (preview tier)",
     "license_verified": lic},
    {"source": "Binance public market data (spot monthly 1h klines)",
     "source_url": "https://data.binance.vision/",
     "retrieved_at": None,
     "transform": "immutable monthly zips read directly; timestamps ms->ISO-8601 UTC; month derived from timestamp",
     "artifact": "crypto_market.db",
     "license": "Binance public data terms of use",
     "license_verified": lic},
]

manifest = {
    "schema_version": 1,
    # The mobility manifest carries "version" top-level and the deployment
    # loader's version check keys on exactly that (a substring match would
    # also hit artifact_version — the loader's extraction is tightened
    # against that trap).
    "version": version,
    "family": "trade",
    "artifact_version": version,
    "window": {"start": w_start, "end": w_end},
    "artifacts": artifacts,
    # The key is "tables", not "table_checksums" — load.sh's fixed-shape
    # parse cuts the artifacts list at '],"tables"' and the FIRST build run
    # (2026-09-04) diverged on this exact key name: the cutoff missed, the
    # greedy sha256 extraction ran into these records, and the loader read
    # the trade_monthly table checksum as us_trade.duckdb's file SHA.
    "tables": table_checksums,
    "provenance": provenance,
}
json.dump(manifest, sys.stdout, indent=2)
print()
PY

log "manifest written (license_verified is '${LICENSE_VERIFIED:-null}' — publishing with null blocks go-live, design §8)" >&2
