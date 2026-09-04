# Sample data (trade family) — build and publish runbook

> **This product uses the Census Bureau Data API but is not endorsed or certified
> by the Census Bureau.**
>
> Verbatim and mandatory — a condition of the Census API terms of service, and it
> travels with this data everywhere it goes: here, the `sample-trade-us` datasource
> description, and the `notice` field of the Census provenance entry in
> `manifest.json`. Nothing that describes this family may read "Census-verified",
> "Census-powered" or anything else implying endorsement.

**Source: UN Comtrade (https://comtradeplus.un.org)** — the mirror-statistics
slice. It is deliberately fewer than 100,000 records (27 rows: 5 reporters ×
3 years × 2 flows, annual TOTAL grain), because 100,000 records is Comtrade's
fee-free re-dissemination line and staying under it is what makes this slice
free to publish.

**Source: Board of Governors of the Federal Reserve System, H.10** — the
exchange rates. A work of the US Government, so no copyright and no attribution
requirement; the credit is courtesy.

Deterministic build scripts for the `trade/v2` sample-data artifact set. This
family mirrors [`../sample-data/`](../sample-data/README.md) (the design doc
of record is the same: [`docs/superpowers/specs/2026-08-16-sample-data-design.md`](../../docs/superpowers/specs/2026-08-16-sample-data-design.md))
and shares its lib/ machinery. Differences from the mobility family are
structural, not philosophical — see "What is different" below.

One business domain, three engines (the demo's fourth dialect debuts here):

| Artifact | Engine | Contents |
|---|---|---|
| `us_trade.duckdb` | DUCKDB | US Census facts: monthly imports/exports at HS-6 grain for the top-15 partners (data-derived, 2023-01..2024-12), plus partner / HS-chapter lookups and the Census↔ISO crosswalk |
| `mysql-trade.sql.gz` | MYSQL | UN Comtrade mirror statistics: what the top-5 partners REPORT trading with the USA, annual TOTAL level 2022-2024 |
| `fx_rates.db` | SQLITE | Federal Reserve H.10 daily noon buying rates and G.5 monthly averages for CAD, CNY, EUR, JPY and MXN, 2023-01..2024-12, plus the Census-partner→currency lookup |
| `examples.json` | — | Seeded templates + pipelines (trade balance, mirror reconciliation, imports in partner currency) |
| `manifest.json` | — | Checksums, sizes, restore hints, per-table fingerprints, provenance |

## Datasource names (the contract the examples assume)

`sample-trade-us` (DUCKDB, jdbc:duckdb:), `sample-trade-world` (MYSQL),
`sample-fx` (SQLITE, jdbc:sqlite:). The seeded pipelines reference these
names; deployment registration must use them.

## Build

```bash
export CENSUS_API_KEY=<key>   # https://api.census.gov/data/key_signup.html — never persisted
./scripts/sample-data-trade/download.sh        # 1. API pulls + H.10 packages -> work/raw/ (~30 min, 4 workers)
./scripts/sample-data-trade/pin.sh             #    MAINTAINER: record the extract pins (bootstrap once)
./scripts/sample-data-trade/transform.sh       # 2. DuckDB ETL + H.10 reshape -> work/csv/
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
- **The Federal Reserve DDP is a query with a fixed answer.** For a CLOSED
  historical window the Data Download Program returns the same bytes every
  time (measured), so its two CSV packages are pinned as downloaded files
  rather than as merged extracts. Two quirks are handled in `download.sh`: it
  refuses a request with no browser `User-Agent`, and it wants the window as
  `MM/DD/YYYY` — derived from `window_start`/`window_end`, never written twice.
- **Both rate directions are stored, so no template divides.** `per_usd` is
  always foreign-currency-per-USD and `usd_per` always its inverse. The euro
  series is published the other way round (US$ per EUR) and is inverted at
  build; the direction is established from the Board's own `RXI$US` series-id
  prefix, cross-checked against the monthly package's `Unit:` row and against
  the daily rates themselves (every monthly average must land within 2% of the
  mean of that month's dailies). `ND` observations — days a rate was not
  published — are DROPPED, never zero-filled.
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
aws s3 cp . s3://datapipelines-co/sample-data/trade/v2/ --recursive --acl public-read
aws s3 ls s3://datapipelines-co/sample-data/trade/v2/
```

Verify from a clean directory (as a consumer), re-run `verify.sh` against it,
and run the published-drift guard for this family:

```bash
./scripts/sample-data/check-published.sh --family trade v2
```

Version directories are immutable — any change is a new version. `v1` is not
edited; it is superseded. Its market-data object was removed from the bucket by
the owner when the licence ruling of 2026-09-04 dropped that source, so `v1` is
incomplete by design and nothing should be pinned to it.
