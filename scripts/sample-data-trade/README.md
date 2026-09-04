# Sample data (trade family) — build and publish runbook

Deterministic build scripts for the `trade/v1` sample-data artifact set. This
family mirrors [`../sample-data/`](../sample-data/README.md) (the design doc
of record is the same: [`docs/superpowers/specs/2026-08-16-sample-data-design.md`](../../docs/superpowers/specs/2026-08-16-sample-data-design.md))
and shares its lib/ machinery. Differences from the mobility family are
structural, not philosophical — see "What is different" below.

One business domain, three engines (the demo's fourth dialect debuts here):

| Artifact | Engine | Contents |
|---|---|---|
| `us_trade.duckdb` | DUCKDB | US Census facts: monthly imports/exports at HS-6 grain for the top-15 partners (data-derived, 2023-01..2024-12), plus partner / HS-chapter lookups and the Census↔ISO crosswalk |
| `mysql-trade.sql.gz` | MYSQL | UN Comtrade mirror statistics: what the top-5 partners REPORT trading with the USA, annual TOTAL level 2022-2024 |
| `crypto_market.db` | SQLITE | Binance hourly klines (BTCUSDT, ETHUSDT), 2023-01..2024-12 |
| `examples.json` | — | Seeded templates + pipelines (trade balance, mirror reconciliation, market range) |
| `manifest.json` | — | Checksums, sizes, restore hints, per-table fingerprints, provenance |

## Datasource names (the contract the examples assume)

`sample-trade-us` (DUCKDB, jdbc:duckdb:), `sample-trade-world` (MYSQL),
`sample-market` (SQLITE, jdbc:sqlite:). The seeded pipelines reference these
names; deployment registration must use them.

## Build

```bash
export CENSUS_API_KEY=<key>   # https://api.census.gov/data/key_signup.html — never persisted
./scripts/sample-data-trade/download.sh        # 1. API pulls + zips -> work/raw/ (~30 min, 4 workers)
./scripts/sample-data-trade/pin.sh             #    MAINTAINER: record the extract pins (bootstrap once)
./scripts/sample-data-trade/transform.sh       # 2. DuckDB ETL -> work/csv/
./scripts/sample-data-trade/load-and-dump.sh   # 3. artifacts + checksums -> work/artifacts/
./scripts/sample-data-trade/manifest.sh > scripts/sample-data-trade/work/artifacts/manifest.json
./scripts/sample-data-trade/verify.sh          # the proof: re-derive everything from the artifacts
```

## What is different from the mobility family

- **Living APIs, not immutable files.** Census and Comtrade are queried, not
  downloaded; the pinned object (sha256 in sources.lock) is the merged
  CANONICAL JSONL EXTRACT download.sh builds — the same content-pin idea as
  the NOAA `window` pins. Census revises annually (April): a re-pull after a
  revision fails the pin loudly, which is the event worth failing on.
- **The API key never lives in a file.** `CENSUS_API_KEY` is exported at
  download time; sources.lock documents the query, not the credential.
- **DuckDB is a runtime dialect here** (the artifact IS a .duckdb file) —
  unlike the mobility family where it is build-tooling only (their design
  D2). The product ships the MIT-licensed JDBC driver in core, so the demo
  needs no special build flag for this family (MySQL still needs `-Pmysql`
  for `sample-trade-world`, as ever).
- **No sampling.** The slice is fully described by the params (partners,
  window); determinism rests on the pins + totally-ordered CSVs.
- **4-worker parallel Census fetch.** Sequential measured ~6 calls/min
  (server-side latency), 108 min for 720 calls; 4 workers ≈ 24/min. Worker
  failures are collected and the stage dies if any call failed, so a partial
  cache can never reach the merge.

## Publish (owner's step — after the licence gate)

Same as the mobility family: `license_verified` ships null until the owner
checks each source's CURRENT terms (evidence links in sources.lock) and sets
the param. Then:

```bash
cd scripts/sample-data-trade/work/artifacts
aws s3 cp . s3://datapipelines-co/sample-data/trade/v1/ --recursive --acl public-read
aws s3 ls s3://datapipelines-co/sample-data/trade/v1/
```

Verify from a clean directory (as a consumer) and re-run `verify.sh` against
it. Version directories are immutable — any change is `v2`.
