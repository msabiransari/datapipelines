# Sample data — build and publish runbook

Deterministic build scripts that turn pinned public sources into the published
sample-data artifact set. Design:
[`docs/superpowers/specs/2026-08-16-sample-data-design.md`](../../docs/superpowers/specs/2026-08-16-sample-data-design.md)
(§3 datasets, §4 artifact layout, §4.1 this pipeline, §8 the licence gate).

One business domain, three engines:

| Artifact | Engine | Contents |
|---|---|---|
| `pg-trips.dump` | POSTGRES | NYC TLC yellow-taxi trips, hash-sampled from a pinned 24-month window, plus daily and monthly rollups |
| `mysql-weather.sql.gz` | MYSQL | NOAA GHCN-Daily observations for five NYC-area stations over the same window, plus station metadata |
| `nyc_reference.db` | SQLITE | TLC taxi zones, rate-code and payment-type lookups, and a weekend / US-federal-holiday calendar |
| `examples.json` | — | The example templates and pipelines seeded into each personal workspace |
| `manifest.json` | — | Checksums, sizes, restore hints, per-table fingerprints, provenance |

The deployment side — downloading, verifying and restoring these — is
[`deploy/sample-data/load.sh`](../../deploy/sample-data/load.sh), documented for
operators in [deployment.md Appendix B](../../docs/deployment.md).

---

## Prerequisites

- **Docker.** The throwaway build engines are digest-pinned Postgres and MySQL
  images; nothing is installed on the host.
- **`sqlite3`.** The reference artifact *is* a SQLite file, built with the CLI.
  Any version: the file format is stable across releases, which is exactly why
  SQLite and not DuckDB is the embedded engine in v1 (design D2).
- **`python3`**, **`curl`**, **`unzip`**, **`gzip`**, **`shasum`**.
- **~4 GB of free disk** and a fast link: the pinned sources are ~1.2 GB.

The DuckDB CLI is *not* a prerequisite — it is downloaded and SHA-256-verified
into `.tools/` on first use, exactly like the scanner binaries
(DEVELOPMENT.md §10.2).

## Build

Four stages, each re-runnable on its own so a failure does not repeat the work
before it:

```bash
# Every path below is relative to the repo root; the working directory is
# scripts/sample-data/work/ (git-ignored).
./scripts/sample-data/download.sh        # 1. fetch + verify pinned sources -> work/raw/
./scripts/sample-data/transform.sh       # 2. DuckDB ETL                    -> work/csv/
./scripts/sample-data/load-and-dump.sh   # 3. throwaway engines + dumps     -> work/artifacts/
./scripts/sample-data/manifest.sh        # 4. manifest.json                 -> work/artifacts/
./scripts/sample-data/verify.sh          # proof: re-derive everything from the artifacts
```

`work/` and `.tools/` are git-ignored. Stage 3 takes the longest — it loads ~4.9M
rows into Postgres and fingerprints them.

## What makes the artifacts reproducible

Everything here exists to make one claim true: **the same `sources.lock` and the
same scripts produce the same table contents, on any machine, on any day.**

- **Every source URL and its SHA-256 are pinned** in `sources.lock`, verified
  after download. An upstream file that is silently re-published fails the build
  loudly, naming the file. `download.sh` can only verify; re-pinning is a
  separate, deliberate command (`pin.sh`) whose diff is the record of what
  changed upstream.
- **Two sources are deliberately unpinned at the file level** and pinned at the
  *content* level instead. NCEI regenerates every GHCN file nightly and publishes
  no dated snapshot, so a file SHA would fail every day for a reason that is not
  drift — training everyone to re-pin on sight, which is the habit a pin exists
  to prevent. `sources.lock` marks them `unpinned:appended-daily` and carries a
  `window` line per station: the SHA-256 of exactly the rows this build extracts.
  Appended future days cannot change it; a revision of historical observations
  fails the build, which is the event worth failing on.
- **Sampling is hash-based, never random.** `hash(<natural key columns>) %
  modulus = 0` selects the same trips everywhere. DuckDB's `hash()` is stable
  within a version, and the version is pinned — which is why bumping DuckDB is an
  artifact-version bump.
- **Every emitted CSV is totally ordered**, and `trip_id` is assigned by
  `row_number()` over that order rather than by a sequence.
- **The tools are pinned too**: the DuckDB CLI by version + per-platform SHA-256,
  and the build engines by image *digest* — `pg_dump`'s custom format is tied to
  the server major, and the demo restores with that same major.
- **The determinism contract is about CONTENTS, not dump bytes.** `pg_dump`
  output legitimately differs between tool builds. `manifest.json` therefore
  records, per table, the row count and a checksum of the engine's own ordered
  row stream (`checksums.spec` names the tables and their ordering keys), and
  `verify.sh` re-derives exactly those from the artifacts.

To *prove* it rather than assert it, build twice and compare the fingerprints:

```bash
W=scripts/sample-data/work/artifacts
./scripts/sample-data/load-and-dump.sh && cp "$W/checksums.tsv" /tmp/a.tsv
./scripts/sample-data/load-and-dump.sh && diff /tmp/a.tsv "$W/checksums.tsv" && echo IDENTICAL
```

## Changing the data

Any change to what is published is a **new version directory** — `v2`, never an
edit to `v1` (design §4: version directories are immutable, and consumers pin the
version). The inputs that change the artifact all live in the `param` block of
`sources.lock`: the window, the sampling modulus, the station set, the DuckDB
version. Edit a param, run `pin.sh`, run `transform.sh --repin`, review both
diffs, bump `param artifact_version`, rebuild, verify, publish.

## The licence gate (design §8) — READ BEFORE PUBLISHING

Every `provenance` row in `manifest.json` ships **`license_verified: null`**.
That is not an oversight and not a defect: this build verifies no licence and
claims none. The licence strings are the design's research claims, carried
forward with their evidence links in the comment header of `sources.lock`.

**Publishing with any `license_verified` still null blocks go-live.** The owner
checks each source's current terms, records the date, and only then publishes. A
dataset that fails verification is swapped, not shipped. `verify.sh` reports how
many rows are still null; it does not fail on it, because an unpublished build is
expected to be in exactly that state.

## Publish (owner's step)

The published bucket is `datapipelines-co` (us-east-1). Publish history: **v1
published 2026-08-29 with the licence gate stamped; v2 published 2026-09-02 with
the licence gate re-stamped the same day** (every evidence link re-fetched, the
operative quotes unchanged — handback 049). v2 republishes the identical data
artifacts (byte-identical MySQL and SQLite dumps; the Postgres dump differs only
in its 5-byte header create-date — the README's contents-not-bytes contract) with
the corrected `examples.json` (042's parameter-interpolated migration, which v1
predates — T70). A re-publication is a NEW version directory (v3, …) —
never an overwrite of an existing one.

```bash
cd scripts/sample-data/work/artifacts
aws s3 cp . s3://datapipelines-co/sample-data/mobility/v2/ --recursive --acl public-read
aws s3 ls s3://datapipelines-co/sample-data/mobility/v2/
```

Then verify the published copy the way a consumer will, from a clean directory:

```bash
mkdir -p /tmp/pub && cd /tmp/pub
curl -fsSLO https://datapipelines-co.s3.amazonaws.com/sample-data/mobility/v2/manifest.json
# ...and the four artifacts it lists, then:
./scripts/sample-data/verify.sh /tmp/pub
```

Finally run the published-drift guard (049 C2) — the step whose absence let the
published v1 drift from this repo for two days (T70):

```bash
./scripts/sample-data/check-published.sh v2   # the version just published
```

It fails unless the published `examples.json`, the published manifest's declared
checksum, and `content/examples.json` here all agree. It is also the rehearsal
step for every later release (deployment.md Appendix B).

Finally set `SAMPLE_BASE_URL` in `deploy/.env` to
`https://datapipelines-co.s3.amazonaws.com/sample-data/mobility` (deployment.md
Appendix B).

## Why this is not a gate task

`verify.sh` needs Docker, several GB and minutes. Wiring it into `./gradlew
build` would make the standard gate non-hermetic and slow for every change in the
repo, to prove something about an artifact that changes only when someone
deliberately rebuilds it. It is a documented, runnable procedure whose output
belongs in the build record for a publication — not a CI-path test.

## Layout

```
scripts/sample-data/
  sources.lock        every pin, plus the licence evidence links
  pin.sh              MAINTAINER: regenerates the derived pins (never automatic)
  download.sh         stage 1 — fetch + verify
  transform.sh        stage 2 — DuckDB ETL; --repin rewrites the NOAA content pins
  load-and-dump.sh    stage 3 — throwaway engines, row-count asserts, dumps
  manifest.sh         stage 4 — manifest.json
  verify.sh           the proof: re-derive everything from the artifacts
  check-published.sh  the drift guard: published examples.json == repo copy == manifest
  checksums.spec      which tables are fingerprinted, and in what order
  ddl/                per-engine schema
  data/               pinned reference data a URL cannot give us (a PDF, an HTML page)
  content/            examples.json — the seeded example templates and pipelines
  lib/                shared shell: pins, the DuckDB install, the build engines
  work/               git-ignored: raw sources, intermediate CSVs, built artifacts
```
