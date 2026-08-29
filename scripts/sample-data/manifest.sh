#!/usr/bin/env bash
# scripts/sample-data/manifest.sh — stage 4 of the sample-data build
# (design §4/§4.1): assemble work/artifacts/manifest.json.
#
#   ./scripts/sample-data/manifest.sh
#
# The manifest is what every consumer trusts: deploy/sample-data/load.sh
# checksum-verifies each artifact against it before touching any engine, and
# verify.sh re-derives every row count and content checksum in it from the
# artifacts themselves.
#
# LICENSE GATE (design §8). Every provenance row ships `license_verified: null`.
# This build verifies no licence and claims none — the licence strings are the
# design's research claims, carried forward with their evidence links in
# sources.lock. Flipping a null to a date is the OWNER's act after checking the
# current terms, and deployment.md states that publishing with any null still
# present blocks go-live.

set -euo pipefail
SD_SCRIPT=manifest
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
source "$SD_ROOT/lib/common.sh"

ART="$SD_ROOT/work/artifacts"
[ -f "$ART/checksums.tsv" ] || die "work/artifacts/checksums.tsv is missing — run load-and-dump.sh first"

VERSION=$(lock_field param artifact_version)
WINDOW_START=$(lock_field param window_start)
WINDOW_END=$(lock_field param window_end)
MODULUS=$(lock_field param trips_sample_modulus)
DUCKDB_VERSION=$(lock_field param duckdb_version)

python3 - "$ART" "$SD_ROOT" "$SOURCES_LOCK" "$VERSION" "$WINDOW_START" "$WINDOW_END" "$MODULUS" "$DUCKDB_VERSION" <<'PY'
import hashlib, json, os, sys, datetime, collections

art, sd_root, lock_path, version, w_start, w_end, modulus, duckdb_version = sys.argv[1:9]

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def lock(kind, key, col=2):
    for line in open(lock_path, encoding="utf-8"):
        if line.lstrip().startswith("#"):
            continue
        f = line.split()
        if len(f) > col and f[0] == kind and f[1] == key:
            return f[col]
    return None

# The window as inclusive DATE bounds — `window_end` names a MONTH, so its bound
# is the last day of that month. Computed, never written as a literal, and the
# same derivation transform.sh applies; a hand-written end date is how the
# manifest ends up describing a window the data does not have.
_wy, _wm = (int(x) for x in w_end.split("-"))
_ny, _nm = (_wy + 1, 1) if _wm == 12 else (_wy, _wm + 1)
day_start = f"{w_start}-01"
day_end = (datetime.date(_ny, _nm, 1) - datetime.timedelta(days=1)).isoformat()


def retrieved(raw_name):
    """When this build last downloaded the source. mtime of the raw file — the
    one honest answer available; there is no upstream 'published at'."""
    p = os.path.join(sd_root, "work", "raw", raw_name)
    if not os.path.exists(p):
        return None
    return datetime.datetime.fromtimestamp(os.path.getmtime(p), datetime.timezone.utc)\
        .replace(microsecond=0).isoformat().replace("+00:00", "Z")

# --- per-table counts and content checksums (from load-and-dump.sh) ---------
tables = []
counts = collections.defaultdict(int)
for line in open(os.path.join(art, "checksums.tsv"), encoding="utf-8"):
    engine, table, n, ck = line.rstrip("\n").split("\t")
    tables.append(collections.OrderedDict([
        ("engine", engine.upper()), ("table", table),
        ("row_count", int(n)), ("checksum", "sha256:" + ck)]))
    counts[table] = int(n)

# --- artifacts --------------------------------------------------------------
ARTIFACTS = [
    ("pg-trips.dump", "POSTGRES",
     "pg_restore --no-owner --no-privileges --dbname dp_sample_trips pg-trips.dump "
     "(server major 16 — the dump is pg_dump -Fc and is not forward-compatible)"),
    ("mysql-weather.sql.gz", "MYSQL",
     "gunzip -c mysql-weather.sql.gz | mysql (the dump carries CREATE DATABASE dp_sample_weather)"),
    ("nyc_reference.db", "SQLITE",
     "copy onto a read-only volume the app container mounts, e.g. /srv/sample/nyc_reference.db"),
    ("examples.json", None,
     "mount and point datapipelines.bootstrap.examples-file at it (configuration.md §3.18)"),
]
artifacts = []
for name, engine, hint in ARTIFACTS:
    p = os.path.join(art, name)
    if not os.path.exists(p):
        sys.exit(f"manifest: FAIL — artifact '{name}' is missing from work/artifacts/")
    entry = collections.OrderedDict([("file", name)])
    if engine:
        entry["engine"] = engine
    entry["sha256"] = sha256(p)
    entry["bytes"] = os.path.getsize(p)
    entry["restore_hint"] = hint
    artifacts.append(entry)

# --- provenance -------------------------------------------------------------
#
# license_verified is null on EVERY row, deliberately (design §8). The evidence
# links live in sources.lock next to the pins they justify.
tlc_months = sorted(k for k in [l.split()[1] for l in open(lock_path, encoding="utf-8")
                                if l.split() and l.split()[0] == "url"]
                    if k.startswith("tlc-yellow-"))
provenance = [
    collections.OrderedDict([
        ("dataset", "nyc_tlc_yellow_trips"),
        ("source_url", "https://d37ci6vzurychx.cloudfront.net/trip-data/yellow_tripdata_{YYYY-MM}.parquet"),
        ("source_files", len(tlc_months)),
        ("retrieved_at", retrieved(tlc_months[0]) if tlc_months else None),
        ("transform",
         f"filter to pickup dates {day_start}..{day_end} and to valid zone ids/non-negative amounts, "
         f"hash-sample hash(natural key) % {modulus} = 0 (no RNG), normalize codes, "
         f"emit trips + daily and monthly rollups"),
        ("row_count", counts.get("trips", 0)),
        ("license", "NYC Open Data / freely usable (nyc.gov terms of use)"),
        ("license_verified", None),
    ]),
    collections.OrderedDict([
        ("dataset", "noaa_ghcn_daily"),
        ("source_url", "https://www.ncei.noaa.gov/data/global-historical-climatology-network-daily/access/{STATION}.csv"),
        ("source_files", 5),
        ("retrieved_at", retrieved("noaa-ghcn-USW00094728.csv")),
        ("transform",
         f"restrict to the five pinned NYC-area stations and to obs dates {day_start}..{day_end}, melt the wide "
         "element columns to one row per station/day/element for PRCP, SNOW, TMAX, TMIN, AWND, "
         "convert GHCN native units to mm / degC / m per s"),
        ("row_count", counts.get("observations", 0)),
        ("license", "US Government work, public domain (NOAA/NCEI)"),
        ("license_verified", None),
    ]),
    collections.OrderedDict([
        ("dataset", "noaa_ghcn_stations"),
        ("source_url", "https://www.ncei.noaa.gov/pub/data/ghcn/daily/ghcnd-stations.txt"),
        ("source_files", 1),
        ("retrieved_at", retrieved("ghcnd-stations.txt")),
        ("transform", "fixed-width slice of the five pinned station rows (id, name, lat, lon, elevation)"),
        ("row_count", counts.get("stations", 0)),
        ("license", "US Government work, public domain (NOAA/NCEI)"),
        ("license_verified", None),
    ]),
    collections.OrderedDict([
        ("dataset", "tlc_taxi_zones"),
        ("source_url", "https://d37ci6vzurychx.cloudfront.net/misc/taxi_zone_lookup.csv"),
        ("source_files", 1),
        ("retrieved_at", retrieved("tlc-zone-lookup.csv")),
        ("transform", "pass-through, dropping rows with a null borough/zone/service zone"),
        ("row_count", counts.get("zones", 0)),
        ("license", "NYC Open Data / freely usable (nyc.gov terms of use)"),
        ("license_verified", None),
    ]),
    collections.OrderedDict([
        ("dataset", "tlc_reference_codes"),
        ("source_url", "https://www.nyc.gov/site/tlc/about/tlc-trip-record-data.page (Data Dictionary - Yellow Taxi Trip Records, PDF)"),
        ("source_files", 2),
        ("retrieved_at", None),
        ("transform",
         "transcribed by hand into scripts/sample-data/data/tlc-rate-codes.csv and "
         "tlc-payment-types.csv (the dictionary is a PDF); the build asserts every code present "
         "in the sample has a row"),
        ("row_count", counts.get("rate_codes", 0) + counts.get("payment_types", 0)),
        ("license", "NYC Open Data / freely usable (nyc.gov terms of use)"),
        ("license_verified", None),
    ]),
    collections.OrderedDict([
        ("dataset", "us_federal_holidays"),
        ("source_url", "https://www.opm.gov/policy-data-oversight/pay-leave/federal-holidays/"),
        ("source_files", 1),
        ("retrieved_at", None),
        ("transform",
         "one calendar row per day of the window with weekend and holiday flags; the holiday set "
         "is re-derived from the statutory rules (5 U.S.C. 6103) at build time and the build fails "
         "if it disagrees with the committed OPM transcription"),
        ("row_count", counts.get("calendar", 0)),
        ("license", "Facts (statutory schedule); no licence claimed"),
        ("license_verified", None),
    ]),
]

manifest = collections.OrderedDict([
    ("schema_version", 1),
    ("dataset", "mobility"),
    ("version", version),
    ("built_at", datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
        .isoformat().replace("+00:00", "Z")),
    ("build", collections.OrderedDict([
        ("window_start", w_start),
        ("window_end", w_end),
        ("trips_sample_modulus", int(modulus)),
        ("duckdb_version", duckdb_version),
        ("postgres_image", lock("image", "postgres")),
        ("mysql_image", lock("image", "mysql")),
    ])),
    ("artifacts", artifacts),
    # Content fingerprints, computed IN the engines that hold the data. These —
    # not the dump bytes — are the determinism contract (design §4.1): pg_dump
    # output legitimately differs between tool builds; the table contents may not.
    ("tables", tables),
    ("provenance", provenance),
])

out = os.path.join(art, "manifest.json")
with open(out, "w", encoding="utf-8") as f:
    json.dump(manifest, f, indent=2, ensure_ascii=False)
    f.write("\n")

unverified = sum(1 for p in provenance if p["license_verified"] is None)
print(f"manifest: wrote {out} — {len(artifacts)} artifacts, {len(tables)} table fingerprints, "
      f"{len(provenance)} provenance rows", file=sys.stderr)
print(f"manifest: LICENCE GATE — {unverified}/{len(provenance)} provenance rows have "
      f"license_verified: null. Publishing with ANY null blocks go-live (design §8).", file=sys.stderr)
PY
