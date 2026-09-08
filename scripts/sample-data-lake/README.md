# dp-lake sample data — build and publish runbook

**dp-lake** is the offering: an object store the pipelines query **in place**. This
family builds what is published at
`s3://datapipelines-co/sample-data/lake/<version>/` — NYC TLC **High Volume
For-Hire Vehicle** trips (Uber, Lyft, Via, Juno) as partitioned Parquet and as an
Apache Iceberg table.

Nothing here is downloaded, restored or loaded at demo start. There is no loader and
no `load-and-dump.sh`: a query names the days it wants and the engine fetches those
objects. That is the whole point of the family, and it is why its shape differs from
[`../sample-data/`](../sample-data/README.md) (mobility) and
[`../sample-data-trade/`](../sample-data-trade/README.md) (trade), which publish
dumps that a loader restores into engines.

The lake **datasource** — dialect `lake`, name `sample-lake` — is round 089. This
round builds and documents the data; nothing in the application reads it yet.

| Table | Format | Shape |
|---|---|---|
| `hvfhv_trips` | Parquet, partitioned by `pickup_date` | Every trip in the window, one directory per day. The big prunable table. |
| `hvfhv_trips_sample` | Parquet, one file per month | The same rows, hash-sampled 1 in 16. The table a first question should hit. |
| `hvfhv_zone_day` | Parquet, one file | Trips, fares, tips, driver pay, miles and shared-ride requests per pickup zone × day × company. |
| `hvfhs_companies` | Parquet, one file | `HV0002 juno`, `HV0003 uber`, `HV0004 via`, `HV0005 lyft`. |
| `hvfhv_trips_iceberg` | Apache Iceberg (format-version 2) | `hvfhv_trips_sample` again, as an Iceberg table, so the same rows can be read both ways and compared. |
| `manifest.json` | — | Every object with SHA-256 and bytes, per-table row counts and content checksums, per-partition counts, provenance, and the `tables[]` block 089 seeds a registry from. |

The zone ids are **the same TLC zone ids** the mobility family's
`sample-reference.zones` holds, which is what lets one pipeline join Postgres
yellow-taxi trips to lake rideshare trips through one borough lookup.

---

## Prerequisites

- **`python3`**, **`curl`**, **`unzip`**, **`shasum`**. No Docker: nothing is
  restored, so there is no engine to start.
- **Disk.** The pinned sources are **11.6 GB** (24 monthly Parquet files, 450–522 MB
  each). The built tables are roughly **7 GB** more. Read the *Low-disk build* recipe
  below before starting on a machine with less than ~25 GB free — it needs about
  8 GB instead, by transforming a month at a time and deleting each raw file.
- **Bandwidth and time.** Measured 2026-09-07 on this project's build box: pinning
  the 24 months (a streamed hash of every byte, nothing written) took ~35 minutes;
  transforming a month took ~25 seconds.

The DuckDB CLI and the pyiceberg environment are *not* prerequisites — both are
pinned and installed into `.tools/` on first use (`sources.lock`'s `tool` lines and
`requirements-pyiceberg.txt`), exactly as the mobility family installs DuckDB.

## Build

```bash
# Every path below is relative to the repo root; the working directory is
# scripts/sample-data-lake/work/ (git-ignored).
./scripts/sample-data-lake/download.sh        # 1. fetch + verify 24 pinned months -> work/raw/
./scripts/sample-data-lake/transform.sh       # 2. DuckDB + pyiceberg ETL         -> work/tables/, work/iceberg/
./scripts/sample-data-lake/publish-layout.sh  # 3. render the bucket tree          -> work/publish/lake/<version>/
./scripts/sample-data-lake/manifest.sh        # 4. manifest.json into that tree
./scripts/sample-data-lake/verify.sh          # proof: re-derive everything from the rendered tree
```

### Low-disk build

`download.sh --month` and `transform.sh --month` exist so the window can be worked a
month at a time. Each raw file is only needed until its month has been transformed:

```bash
S=scripts/sample-data-lake
for m in $(seq -f "2023-%02g" 1 12) $(seq -f "2024-%02g" 1 12); do
  $S/download.sh  --month "$m" &&
  $S/transform.sh --month "$m" &&
  rm -f "$S/work/raw/tlc-hvfhv-$m.parquet"
done
$S/transform.sh --finalize     # companies, the zone/day aggregate, the Iceberg table
```

`transform.sh --finalize` refuses to run until every month of the pinned window has a
sample file: an aggregate over half a window is a plausible, wrong artifact, and it is
the failure this family is most likely to ship if nobody checks.

`publish-layout.sh` **hard-links** rather than copies, so the rendered tree does not
double the disk. `PUBLISH_COPY=1` forces real copies.

## What makes the artifact reproducible

Everything here exists to make one claim true: **the same `sources.lock` and the same
scripts produce the same table CONTENTS, on any machine, on any day.**

- **Every source URL and its SHA-256 are pinned** in `sources.lock`, verified after
  download. A month TLC silently re-publishes fails the build loudly, naming the file.
  `download.sh` can only verify; re-pinning is a separate deliberate command
  (`pin.sh`) whose diff is the record of what changed upstream.
- **Sampling is hash-based, never random.** `hash(<natural key columns>) % 16 = 0`
  selects the same trips everywhere. DuckDB's `hash()` is stable within a version and
  the version is pinned — which is why bumping DuckDB is an artifact-version bump.
- **No surrogate key is invented.** A trip record has no id, and a `row_number()`
  would depend on which months had been transformed when it ran.
- **The determinism contract is CONTENTS, not bytes**, and here it has to be three
  times over: a partitioned Parquet write does not promise a row order inside a file,
  DuckDB may change its encoder between versions, and pyiceberg stamps a fresh table
  UUID, snapshot id and timestamp on every run. So `manifest.json` records **both**:
  each object's SHA-256 and bytes (what a consumer can check about the bytes it was
  served) *and* each table's row count plus a checksum of its ordered row stream
  (`checksums.spec` names the order) — which is what a rebuild must reproduce.
- **The row counts have two independent origins.** `transform.sh` records what its
  own views counted as it wrote; `manifest.sh` counts the written objects and **fails**
  if the two disagree. A partitioned write that silently mis-routed or dropped rows
  is invisible to a check that only asks the output about itself.
- **Every row is in the partition it claims.** `verify.sh` asserts
  `CAST(pickup_at AS DATE) = pickup_date` across the whole table — the one thing a
  table-level row count can never see.
- **The Parquet sample and the Iceberg table must be the same rows.** Both are
  checksummed the same way and `manifest.sh` refuses to write a manifest where they
  differ. (They matched on the first run: `d3cca0dcffe8…` from both sides.)

To *prove* it rather than assert it, build twice and compare the fingerprints — the
manifest's `tables[]`, never the object hashes:

```bash
M=scripts/sample-data-lake/work/publish/lake/v1/manifest.json
python3 -c "import json,sys; print(json.dumps([{k:t[k] for k in ('name','row_count','checksum')} for t in json.load(open('$M'))['tables']], indent=1))"
```

## The Iceberg table, and why it is written the way it is

**DuckDB cannot write it.** Its `iceberg` extension writes only through an attached
Iceberg **REST catalog** — verified 2026-09-07 against the pinned v1.5.5
(`ATTACH '<dir>' (TYPE iceberg)` fails with *"AUTHORIZATION_TYPE is 'oauth2', yet no
'secret' was provided"*) and against the extension's own documentation: *"Catalog-managed
tables are accessed by attaching an Iceberg REST catalog. This unlocks the full feature
set, including writing."* A published S3 prefix has no catalog service. So the writer is
**pinned pyiceberg** (`requirements-pyiceberg.txt`, `param pyiceberg_version`), using a
local SQLite catalog that the writer deletes before it returns — the published table is
catalog-free and is opened by its metadata file.

**An Iceberg table cannot be built somewhere and moved.** It records absolute locations
in `metadata/*.metadata.json`, in the snapshot's manifest-list Avro and in each manifest
Avro. Measured, not assumed (2026-09-07):

- built at `A`, copied to `B`, `A` deleted, then `iceberg_scan('<B>/metadata/…json')`
  → `IO Error: Cannot open file "<A>/metadata/snap-….avro"`;
- the same read with `allow_moved_paths := true` → *worse*: the flag resolves its
  argument as a table **directory**, so a metadata-file argument becomes
  `<file>/metadata/…`;
- the same read **while `A` still existed** succeeded — which is the trap: a check run
  before the staging directory is cleaned proves nothing;
- the Avro manifests are deflate-compressed, so "rewrite the prefix in the bytes"
  is not available either.

So `lib/iceberg_write.py` builds the table **already believing it lives at its final
`s3://` URI**, while the bytes land locally: a ~20-line fsspec filesystem registered for
the `s3` scheme maps `s3://<bucket>/<key>` onto the staging directory. Nothing in this
build talks to S3 and it holds no AWS credentials — the owner uploads.

`lib/iceberg_stat.py` reads the table back **through its own metadata**, walking the
current snapshot's manifests to find the data files, rather than globbing
`<table>/data/`. A glob would be green even with a corrupt metadata file or manifest
list — that is, green in exactly the state that makes the table useless.

## Changing the data

Any change to what is published is a **new version directory** — `v2`, never an edit
to `v1`. The inputs that change the artifact all live in the `param` block of
`sources.lock`: the window, the sampling modulus, the DuckDB version, the pyiceberg
version. Edit a param, run `pin.sh` if the window moved, review the diff, bump
`param artifact_version`, rebuild, verify, publish.

## The licence gate — READ BEFORE PUBLISHING

Every `provenance` row in `manifest.json` ships **`license_verified: null`**. That is
not an oversight and not a defect: this build verifies no licence and claims none.

The research is done and recorded — every page re-fetched on 2026-09-07, every
operative sentence quoted verbatim, including the one difference from the yellow-taxi
position (HVFHV carries its **own** accuracy disclaimer, and this family's provenance
quotes that one, not the TPEP/LPEP sentence the mobility family quotes). The dossier
of record is `notes/2026-09-07-lake-license-dossier.md` in the orchestration store.
Its verdict: the "same publisher, same terms" premise **holds** — both years of the
window are in the NYC Open Data `TLC Trip Data` collection under the same agency
(checked against the Socrata catalog API, not assumed), the TLC page states no terms
of its own, and the programme FAQ says *"There are no restrictions on the use of Open
Data."*

**Publishing with any `license_verified` still null blocks go-live.** The owner checks
the current terms, sets `param license_verified <date>` in `sources.lock`, rebuilds
and only then publishes. `verify.sh` reports how many rows are still null and does not
fail on it, because an unpublished build is expected to be in exactly that state;
`check-published.sh` **does** fail on it, because a published artifact must not be.

**Attribution shipped with the data** (courtesy, not an obligation either page
imposes): the publisher is the NYC Taxi & Limousine Commission, the source page and
the NYC Open Data collection are named in every provenance row, and no
"NYC-endorsed" / "City-verified" phrasing appears anywhere.

## Publish (owner's step)

The published bucket is `datapipelines-co` (us-east-1). **This lane never touches the
bucket.** From the verified tree:

```bash
cd scripts/sample-data-lake/work/publish/lake/v1
aws s3 cp . s3://datapipelines-co/sample-data/lake/v1/ --recursive --acl public-read
aws s3 ls --recursive --summarize s3://datapipelines-co/sample-data/lake/v1/ | tail -3
```

Then confirm the published copy the way a consumer will:

```bash
./scripts/sample-data-lake/check-published.sh --family lake v1
```

It fetches the published `manifest.json`, checks it declares the version of the
directory it came from, **fails if any provenance row still has
`license_verified: null`**, fetches one object per table and compares SHA-256, and
fetches the Iceberg metadata file to confirm every location it records sits inside the
published prefix — the failure that would otherwise be found by a user, because
nothing about an object listing shows it.

### Egress — what serving this actually costs

The point of the layout is that the big table is only read when a query names it:

| Object set | Bytes | When it is read |
|---|---|---|
| `hvfhv_zone_day` + `hvfhs_companies` | single-digit MB | Every run of the showcase pipeline. |
| `hvfhv_trips_sample` | ~500 MB (24 files) | A question that wants trip-level detail over the whole window; one file per month, so a one-month question reads ~21 MB. |
| `hvfhv_trips` | ~7 GB (~730 objects) | Only the day partitions a query's date predicate names. A one-week question reads ~7 objects. |
| `hvfhv_trips_iceberg` | ~500 MB | The Iceberg demo path. |

A demo session that runs the shipped pipeline at its default one-month window reads a
few MB, not gigabytes. The exposure is a visitor who writes `SELECT * FROM
hvfhv_trips` with no date predicate: that is a full-table scan of ~7 GB, at
S3's egress price, per run. Worth a bucket-level request-rate or budget alarm before
the demo is announced.

## Why this is not a gate task

`verify.sh` reads the whole published corpus and re-hashes every table's ordered row
stream: several GB of I/O and, for the full window, tens of minutes. Wiring it into
`./gradlew build` would make the standard gate non-hermetic and slow for every change
in the repo, to prove something about an artifact that changes only when somebody
deliberately rebuilds it. It is a documented, runnable procedure whose output belongs
in the build record for a publication — not a CI-path test.

## The content that uses this data

The showcase pipeline lives with the **mobility** family, not here, because it is a
mobility question and four of its five sources are the mobility family's:
[`../sample-data/content/examples-lake.json`](../sample-data/content/examples-lake.json)
— `nyc/mobility/taxi_vs_rideshare`, which joins Postgres yellow-taxi trips, the lake's
`hvfhv_zone_day`, the SQLite zone lookup, the MySQL rain series and a CALCULATOR node.

It is a **separate file** from `content/examples.json` on purpose. The seeder
([`ExampleContentSeeder`](../../modules/web/src/main/kotlin/co/datapipelines/web/bootstrap/ExampleContentSeeder.kt))
takes a comma-separated LIST of examples files and — before 089 — had **no
per-file gate**: a fixture referencing a datasource the deployment lacks fails
workspace provisioning and the login with it, so a lake pipeline inside
`examples.json` would have broken every `--demo nyc` login. 089 §E added the
gate this file now carries — top-level `"requires_datasources": ["sample-lake",
"sample-trips", "sample-reference", "sample-weather"]`, so the file's content
seeds only when every named datasource is registered (all four, not just
`sample-lake`, because the pipeline's other three nodes read the mobility
family's datasources: `--demo trade,lake` without `nyc` must skip the file,
not fail the login). The demo wires it on by adding one path to
`DATAPIPELINES_BOOTSTRAP_EXAMPLES_FILE` under the `demo-lake` profile — and by
adding the file to `SampleDataExamplesContentTest.EXAMPLES_PATHS`.

## Layout

```
scripts/sample-data-lake/
  sources.lock             every pin, plus the licence evidence links
  pin.sh                   MAINTAINER: streams + hashes each month, rewrites the derived pins
  download.sh              stage 1 — fetch + verify (--month for one at a time)
  transform.sh             stage 2 — DuckDB ETL per month, then --finalize
  publish-layout.sh        stage 3 — render the exact bucket tree
  manifest.sh              stage 4 — manifest.json, with the source-side cross-check
  verify.sh                the proof: re-derive everything from the rendered tree
  check-published.sh       the publish-confirmation guard (network; lake family only)
  checksums.spec           which tables are fingerprinted, in what shape, in what order
  requirements-pyiceberg.txt   the pinned Python lock for the Iceberg writer
  data/                    hvfhs-companies.csv — the mapping a PDF is the authority for
  lib/lake.sh              this family's shell helpers (the shared ones are ../sample-data/lib/common.sh)
  lib/iceberg-venv.sh      the pinned pyiceberg environment, installed like the DuckDB CLI
  lib/iceberg_write.py     the Iceberg writer, and why it writes s3:// paths locally
  lib/iceberg_stat.py      reads the table back through its own metadata
  lib/manifest_build.py    assembles manifest.json
  lib/tree-report.py       the rendered-tree listing publish-layout.sh prints
  work/                    git-ignored: raw sources, built tables, the rendered tree
```
