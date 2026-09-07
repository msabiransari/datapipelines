"""Assemble work/publish/lake/<version>/manifest.json for the dp-lake artifact.

Invoked by manifest.sh, which has already fingerprinted every table; this file's job
is the document, not the measurement. Kept out of manifest.sh as a real file rather
than a heredoc because it is 200 lines of JSON assembly and a heredoc that long is a
file nobody can lint (the mobility family's manifest.sh inlines its Python and is at
the limit of that).

The manifest has one job the mobility family's does not: **089 seeds a datasource
registry from `tables[]`**. Every field a registration needs — name, format, the
path or glob relative to the version directory, the absolute location, the partition
column, the row count, the current Iceberg metadata file — is there, so nothing about
this layout has to be retyped in the app.
"""

from __future__ import annotations

import argparse
import collections
import datetime
import hashlib
import json
import os
import sys


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def lock_params(lock_path: str) -> dict[str, str]:
    params: dict[str, str] = {}
    for line in open(lock_path, encoding="utf-8"):
        if line.lstrip().startswith("#"):
            continue
        fields = line.split(None, 2)
        if len(fields) > 2 and fields[0] == "param":
            params[fields[1]] = fields[2].strip()
    return params


def lock_ids(lock_path: str, kind: str) -> list[str]:
    out = []
    for line in open(lock_path, encoding="utf-8"):
        if line.lstrip().startswith("#"):
            continue
        fields = line.split()
        if len(fields) >= 2 and fields[0] == kind:
            out.append(fields[1])
    return out


def retrieved(raw_dir: str, name: str) -> str | None:
    """When this build last downloaded the source. mtime of the raw file — the one
    honest answer available; there is no upstream 'published at'. None once the raw
    file has been pruned, which the low-disk build does on purpose."""
    path = os.path.join(raw_dir, name)
    if not os.path.exists(path):
        return None
    return (
        datetime.datetime.fromtimestamp(os.path.getmtime(path), datetime.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--tree", required=True)
    ap.add_argument("--fingerprints", required=True)
    ap.add_argument("--lock", required=True)
    ap.add_argument("--prefix", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--window-start", required=True)
    ap.add_argument("--window-end", required=True)
    ap.add_argument("--day-start", required=True)
    ap.add_argument("--day-end", required=True)
    ap.add_argument("--modulus", required=True)
    ap.add_argument("--duckdb-version", required=True)
    ap.add_argument("--pyiceberg-version", required=True)
    ap.add_argument("--iceberg-summary", required=True)
    ap.add_argument("--raw-dir", required=True)
    args = ap.parse_args()

    tree = os.path.abspath(args.tree)
    prefix = args.prefix.rstrip("/")
    params = lock_params(args.lock)

    # The same objects over plain HTTPS, which is how DuckDB's httpfs reads them
    # without credentials. Derived from the s3:// prefix rather than written twice:
    # two spellings of one location is how a base URL and a bucket drift apart.
    if not prefix.startswith("s3://"):
        sys.exit(f"manifest: FAIL — publish prefix is not an s3:// URI: {prefix!r}")
    bucket, _, key = prefix[len("s3://") :].partition("/")
    https_base = f"https://{bucket}.s3.amazonaws.com/{key}"

    license_verified: str | None = params.get("license_verified")
    if license_verified in (None, "null", ""):
        license_verified = None

    # --- objects -----------------------------------------------------------
    #
    # EVERY published object, with its sha256 and bytes. manifest.json itself is the
    # only file in the tree that is not listed, because it cannot contain its own
    # hash; verify.sh knows that and checks the tree has no OTHER unlisted file.
    objects = []
    total_bytes = 0
    for directory, _, files in os.walk(tree):
        for name in sorted(files):
            path = os.path.join(directory, name)
            key = os.path.relpath(path, tree)
            if key == "manifest.json":
                continue
            size = os.path.getsize(path)
            total_bytes += size
            objects.append(
                collections.OrderedDict(
                    [("key", key.replace(os.sep, "/")), ("sha256", sha256(path)), ("bytes", size)]
                )
            )
    objects.sort(key=lambda o: o["key"])
    if not objects:
        sys.exit("manifest: FAIL — the rendered tree holds no objects")

    # --- tables ------------------------------------------------------------
    iceberg_summary = {}
    if os.path.exists(args.iceberg_summary):
        iceberg_summary = json.load(open(args.iceberg_summary, encoding="utf-8"))

    #: What each table is FOR, in one sentence. Carried in the manifest because the
    #: registry 089 seeds from it is what an agent reads before writing SQL, and a
    #: table with no description is a table an agent guesses about.
    PURPOSE = {
        "hvfhv_trips": (
            "Every high-volume for-hire trip in the window, one row per trip, partitioned by "
            "pickup_date. The big table: only the day partitions a query names are read."
        ),
        "hvfhv_trips_sample": (
            f"The same rows, hash-sampled 1 in {args.modulus} (no RNG), one Parquet file per "
            "month. Small enough to scan whole — the table a first question should hit."
        ),
        "hvfhv_zone_day": (
            "Trips, fares, tips, driver pay, miles and shared-ride requests aggregated per "
            "pickup zone x day x company. Joins to sample-reference.zones on pu_location_id."
        ),
        "hvfhs_companies": (
            "The TLC HVFHS licence-number lookup (HV0002 Juno, HV0003 Uber, HV0004 Via, "
            "HV0005 Lyft), transcribed from the TLC data dictionary."
        ),
        "hvfhv_trips_iceberg": (
            "hvfhv_trips_sample again, as an Apache Iceberg table, so the same rows can be "
            "read through the Iceberg path and the Parquet path and compared."
        ),
    }
    PARTITION_COLUMN = {"hvfhv_trips": "pickup_date"}

    tables = []
    for line in open(args.fingerprints, encoding="utf-8"):
        name, fmt, rows, checksum, location, extra = (line.rstrip("\n").split("\t") + [""])[:6]
        table_objects = [o for o in objects if o["key"] == name or o["key"].startswith(name + "/")]
        entry = collections.OrderedDict(
            [
                ("name", name),
                ("format", fmt),
                # `path` is relative to the version directory and is what a reader
                # appends to the base URL; `location` is the absolute s3:// form.
                # `path` is relative to the version directory (what a reader appends
                # to the base URL); `location` is the absolute s3:// form of the
                # TABLE — for Iceberg that is the table directory, while the current
                # metadata file gets its own field below.
                ("path", name if fmt == "iceberg" else location[len(prefix) + 1 :]),
                ("location", f"{prefix}/{name}" if fmt == "iceberg" else location),
            ]
        )
        if name in PARTITION_COLUMN:
            entry["partition_column"] = PARTITION_COLUMN[name]
        entry["row_count"] = int(rows)
        entry["checksum"] = "sha256:" + checksum
        entry["object_count"] = len(table_objects)
        entry["bytes"] = sum(o["bytes"] for o in table_objects)
        entry["description"] = PURPOSE.get(name, "")
        if extra and os.path.exists(extra):
            # One row per partition: the day, and how many rows are in it. 24 months
            # is ~731 entries and ~25 KB of manifest — worth it, because it is the
            # only assertion in the file that can fail while every table total is
            # right (a row written into the wrong day directory).
            entry["partitions"] = collections.OrderedDict(
                (day, int(count))
                for day, count in (
                    line.rstrip("\n").split("\t")
                    for line in open(extra, encoding="utf-8")
                    if line.strip()
                )
            )
        if fmt == "iceberg":
            entry["metadata_location"] = location
            entry["version_hint"] = f"{prefix}/{name}/metadata/version-hint.text"
            entry["data_files"] = iceberg_summary.get("data_files")
        tables.append(entry)
    if not tables:
        sys.exit("manifest: FAIL — no table fingerprints were supplied")

    # --- provenance --------------------------------------------------------
    months = sorted(i for i in lock_ids(args.lock, "url") if i.startswith("tlc-hvfhv-"))
    trips_rows = next((t["row_count"] for t in tables if t["name"] == "hvfhv_trips"), 0)
    companies_rows = next((t["row_count"] for t in tables if t["name"] == "hvfhs_companies"), 0)
    provenance = [
        collections.OrderedDict(
            [
                ("dataset", "nyc_tlc_hvfhv_trips"),
                ("publisher", "NYC Taxi & Limousine Commission (TLC)"),
                (
                    "source_url",
                    "https://d37ci6vzurychx.cloudfront.net/trip-data/fhvhv_tripdata_{YYYY-MM}.parquet",
                ),
                ("source_page", "https://www.nyc.gov/site/tlc/about/tlc-trip-record-data.page"),
                (
                    "open_data_collection",
                    "https://data.cityofnewyork.us/browse?Data-Collection_Data-Collection=TLC%20Trip%20Data",
                ),
                ("source_files", len(months)),
                ("retrieved_at", retrieved(args.raw_dir, months[0]) if months else None),
                (
                    "transform",
                    f"filter to pickup dates {args.day_start}..{args.day_end}, to zone ids 1..265, to "
                    "non-negative money and distance and a positive trip time, and to a dropoff at or "
                    "after its pickup; project 11 columns; partition by pickup_date; additionally "
                    f"hash-sample hash(natural key) % {args.modulus} = 0 (no RNG) into "
                    "hvfhv_trips_sample and its Iceberg copy; pre-aggregate per zone/day/company",
                ),
                ("row_count", trips_rows),
                ("license", "NYC Open Data / no restrictions on use (NYC Open Data FAQ)"),
                # The accuracy disclaimer that applies to the FHV feed. NOT the
                # yellow/green TPEP/LPEP sentence the mobility family quotes: the TLC
                # page states a different one for these records, and copying the wrong
                # one across would have been a small, plausible, wrong citation.
                (
                    "publisher_notice",
                    "These records are generated from the FHV Trip Record submissions made by bases, "
                    "so we cannot guarantee or confirm their accuracy or completeness.",
                ),
                ("license_verified", license_verified),
            ]
        ),
        collections.OrderedDict(
            [
                ("dataset", "tlc_hvfhs_licensees"),
                ("publisher", "NYC Taxi & Limousine Commission (TLC)"),
                (
                    "source_url",
                    "https://www.nyc.gov/assets/tlc/downloads/pdf/data_dictionary_trip_records_hvfhs.pdf",
                ),
                ("source_page", "https://www.nyc.gov/site/tlc/about/tlc-trip-record-data.page"),
                ("source_files", 1),
                ("retrieved_at", None),
                (
                    "transform",
                    "transcribed by hand into scripts/sample-data-lake/data/hvfhs-companies.csv (the "
                    "data dictionary is a PDF, header dated March 18, 2025); the build fails if the "
                    "window contains a licence number with no row here",
                ),
                ("row_count", companies_rows),
                ("license", "NYC Open Data / no restrictions on use (NYC Open Data FAQ)"),
                (
                    "publisher_notice",
                    "As of September 2019, the HVFHS licensees are the following: HV0002: Juno, "
                    "HV0003: Uber, HV0004: Via, HV0005: Lyft",
                ),
                ("license_verified", license_verified),
            ]
        ),
    ]

    manifest = collections.OrderedDict(
        [
            ("schema_version", 1),
            ("dataset", "lake"),
            ("version", args.version),
            (
                "built_at",
                datetime.datetime.now(datetime.timezone.utc)
                .replace(microsecond=0)
                .isoformat()
                .replace("+00:00", "Z"),
            ),
            ("publish_prefix", prefix),
            # The one sentence that makes this family different from the other two:
            # there is no loader and no restore. A consumer points a query at these
            # objects.
            (
                "access",
                collections.OrderedDict(
                    [
                        ("mode", "read-in-place"),
                        (
                            "note",
                            "Nothing here is downloaded or restored at demo start. The objects are "
                            "read over HTTPS by the query engine; only the partitions a query names "
                            "are fetched.",
                        ),
                        ("https_base", https_base),
                    ]
                ),
            ),
            (
                "build",
                collections.OrderedDict(
                    [
                        ("window_start", args.window_start),
                        ("window_end", args.window_end),
                        ("window_days", f"{args.day_start}..{args.day_end}"),
                        ("trips_sample_modulus", int(args.modulus)),
                        ("duckdb_version", args.duckdb_version),
                        ("pyiceberg_version", args.pyiceberg_version),
                        ("parquet_compression", "zstd"),
                    ]
                ),
            ),
            ("object_count", len(objects)),
            ("bytes", total_bytes),
            # tables[] first: it is what a registry seeds from, and what a human reads.
            ("tables", tables),
            ("objects", objects),
            ("provenance", provenance),
        ]
    )

    out = os.path.join(tree, "manifest.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    unverified = sum(1 for p in provenance if p["license_verified"] is None)
    print(
        f"manifest: wrote {out} — {len(tables)} tables, {len(objects)} objects, "
        f"{total_bytes:,} bytes, {len(provenance)} provenance rows",
        file=sys.stderr,
    )
    print(
        f"manifest: LICENCE GATE — {unverified}/{len(provenance)} provenance rows have "
        "license_verified: null. Publishing with ANY null blocks go-live (design §8).",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
