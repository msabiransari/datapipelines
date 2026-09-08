# lake-fixture — the 089 §F Iceberg test table

`iceberg/trips_iceberg` is a tiny Iceberg table (2000 rows, 2 data files,
~56 KB) the MinIO suites upload to bucket `dp-lake-it` and read through the
app's LAKE datasource.

## Why it is checked in, not generated at test time

An Iceberg table records ABSOLUTE locations in its metadata (metadata.json,
manifest lists, manifests — see `scripts/sample-data-lake/lib/iceberg_write.py`'s
header for the measured `allow_moved_paths` failure modes), so the table must be
BUILT already believing it lives at its final `s3://dp-lake-it/iceberg/trips_iceberg`
URI. Building needs pyiceberg (DuckDB cannot write a catalog-free Iceberg table —
same header), which a test JVM cannot assume. A checked-in fixture is
deterministic and offline; the row content is what matters, not the bytes.

## Schema and content (deterministic)

`id BIGINT, pickup_date DATE, fare DOUBLE, company VARCHAR` — 1000 rows on
`2026-01-01` (ids 0–999), 1000 on `2026-01-02` (ids 1000–1999);
`fare = (id % 97) + 0.5`; `company = 'acme'` for even ids, `'globex'` for odd.

## Regenerating

From the repo root, with the pinned pyiceberg environment
(`scripts/sample-data-lake/lib/iceberg-venv.sh`, created on first use):

1. Write the source parquet with pyarrow exactly as `make_parquet.py` in the
   089 §F handback shows (two days x 1000 rows, the formula above).
2. `mkdir -p /tmp/lake-fixture-stage && ./scripts/sample-data-lake/lib/iceberg-venv.sh run \
     scripts/sample-data-lake/lib/iceberg_write.py \
     --sample-glob '/tmp/lake-fixture-src/part-*.parquet' \
     --stage /tmp/lake-fixture-stage \
     --table-uri s3://dp-lake-it/iceberg/trips_iceberg`
3. Replace this directory with `/tmp/lake-fixture-stage/iceberg/trips_iceberg`.

The BUCKET NAME is load-bearing: the metadata references
`s3://dp-lake-it/...` absolute URIs, so every suite using this fixture must
upload it to a bucket named exactly `dp-lake-it`.

## Register the metadata FILE, not the table root

DuckDB 1.5.5.1's `iceberg_scan('<table root>')` resolves the current metadata
through `version-hint.text` by CONSTRUCTING `v<n>.metadata.json` /
`<n>.metadata.json` filenames, which never match the Iceberg spec's
`%05d-<uuid>.metadata.json` names pyiceberg writes (measured 2026-09-08 against
the pinned `duckdb_jdbc` jar: root, root + explicit `version`, and every
`version_name_format` glob all fail; only the explicit metadata file scans).
The suites therefore register `location =
s3://dp-lake-it/iceberg/trips_iceberg/metadata/<current>.metadata.json`,
discovering the newest metadata file at seed time (the zero-padded `%05d`
prefix sorts lexicographically). The design record's "Iceberg: the table root
holding `metadata/`" does not survive this measurement — a root-registered
Iceberg table fails the pool build at view bind time.
