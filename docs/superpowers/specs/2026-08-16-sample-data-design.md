# Design: Sample Data — Datasets, S3 Distribution, Bootstrap

**Status:** approved design, pre-implementation (drafted 2026-08-16; owner-approved same date — D1 ratified, D7 modified to config-identified admin actor)
**Scope:** spec 2 of 2 for the datapipelines.co demo direction. Depends on
[spec 1 (workspaces)](2026-08-16-workspaces-design.md) for the `global` +
`readonly` datasource flags and the `auto-per-user` provisioning mode.
**Authority note:** config keys, error codes, and DDL below are PROPOSED;
normative text lands in `docs/configuration.md`, `docs/deployment.md`,
`docs/datasources.md`, `docs/metadata-db.md`, `docs/auth.md` (D7 touches
user provisioning) and enums/contract for any new codes at implementation
time, same-commit with code constants (drift-test
rule). Dataset licenses are stated from research, **not yet verified** — §8
makes verification a go-live gate.

---

## 1. Summary

Sample data is a **published, versioned artifact set**: deterministic build
scripts in the product repo produce per-engine dumps of one coherent business
domain, uploaded to public object storage with a checksummed manifest. Any
deployment — the datapipelines.co demo server, the owner's laptop, an
evaluating org's docker-compose — pulls the same artifacts and gets a
byte-identical environment. The app never touches S3 at runtime: **loading
data is a deployment step** (init jobs / compose one-shots), and the app's
only new capability is generic, config-declared **bootstrap datasource
registration** (create-if-absent), which the demo uses to register the sample
datasources as `global` + `readonly`.

One domain, three engines (owner decision 2026-08-16: multiple databases,
one domain; DuckDB dropped for v1, SQLite in):

```
POSTGRES  dp_sample_trips      NYC TLC yellow-taxi trips (the big table, ~5M rows)
MYSQL     dp_sample_weather    NOAA GHCN-Daily observations, NYC stations, matching years
SQLITE    nyc_reference.db     TLC taxi zones/boroughs, rate codes, US federal holidays calendar
```

Cross-datasource pipelines write themselves: revenue by borough (PG×SQLite),
ridership on rainy vs dry days (PG×MySQL×SQLite), holiday-vs-workday demand —
real questions over real data, staged and joined in the existing tempdb.

## 2. Decisions record

| # | Fork | Decision | Rationale |
|---|---|---|---|
| D1 | Domain | **RATIFIED (owner, 2026-08-16):** NYC mobility (taxi trips + weather + reference). Alternative considered and rejected: e-commerce (UCI Online Retail II) | Mobility is the only candidate where **every** database holds real, permissively-licensed data with genuine cross-DB join value. The retail narrative needs fabricated side-databases (owner rejects mock data) and its best real datasets are non-commercially licensed (§8) |
| D2 | Engines | PG + MySQL + SQLite; DuckDB deferred | Owner decision 2026-08-16. SQLite's file format is stable across versions (a DuckDB file dump would require version pinning between build box and every consumer); DuckDB returns when a Parquet/analytics story warrants it — the dialect already exists, purely additive |
| D3 | Engine↔dataset assignment | Trips→PG, weather→MySQL, reference→SQLite | Size dictates: trips need a real server engine; weather (~10⁵ rows) makes MySQL demonstrable without a second big restore; reference tables are exactly what an embedded read-only file engine is for |
| D4 | Distribution | Public S3 bucket (CloudFront optional later), versioned prefix, immutable artifacts + `manifest.json` with SHA-256s | Owner decision 2026-08-16 (S3). GitHub Release assets kept as a possible free-bandwidth mirror — open, non-blocking |
| D5 | Division of labor | Download/verify/restore = deployment scripts (init containers / compose one-shots). App = bootstrap datasource **registration only** | The app must not shell out to `psql`/`mysql` or hold S3 logic; deployments already own infra provisioning. Keeps the runtime dependency-free and the org quickstart a pure compose file |
| D6 | Registration mechanism | Datasource definitions read from a **file** named by new config key `datapipelines.bootstrap.datasources-file` (key unset = feature off); create-if-absent, never updates existing rows | Owner decision 2026-08-16 (file-based, enabled-by-config). Config-over-code: declarative datasource seeding is useful beyond the demo (IaC-style env setup); a separate file keeps the shareable datasource manifest apart from secret-bearing app config. Never-update means an operator's later edits are never clobbered by a restart |
| D7 | Bootstrap actor | **RATIFIED AS MODIFIED (owner, 2026-08-16):** the actor is the configured bootstrap admin — a valid OIDC email. Reuses the existing `datapipelines.auth.bootstrap-admin-email` key (auth §4.4); when bootstrap datasources are configured, startup **pre-provisions** that user's row (if absent) and uses it as `created_by` | `datasources.created_by` is `NOT NULL REFERENCES users(id)` and registration runs before any login. Reusing the existing key avoids a second admin identity with a single authority (configuration.md already defines it). The earlier `system`-user proposal is superseded — the actor is now a real, nameable human. See §6.1 for the provisioning mechanics |
| D8 | Determinism | Build scripts pin exact source-file URLs + date ranges; row sampling (if any) is hash-based, never RNG | Rebuildable artifacts, provable local/prod parity — MISTAKES.md "seed scripts as source of truth" |
| D9 | Example content | 2–3 example pipelines + their templates ship in the artifact; seeded into each `auto-per-user` workspace at provisioning (config-toggled) | An empty workspace demos nothing; a working cross-datasource example is the fastest "aha" for both humans and agents. Kept tiny to bound scope |

## 3. Datasets (licenses: stated, to be verified — §8)

| Dataset | Source | What we take | License claim |
|---|---|---|---|
| NYC TLC Trip Record Data | nyc.gov TLC official Parquet, monthly files | Yellow taxi, a pinned 24-month window, hash-sampled to ~5M rows, normalized into `trips` + monthly summary tables | NYC Open Data / freely usable |
| NOAA GHCN-Daily | NOAA NCEI | Daily observations (PRCP, SNOW, TMAX, TMIN, AWND) for ~5 NYC-area stations, same window + full station metadata | US Gov public domain |
| TLC Taxi Zone Lookup | nyc.gov TLC | 265 zones → `zones(location_id, borough, zone, service_zone)` | NYC Open Data |
| US federal holidays | OPM published list | `calendar(date, is_holiday, holiday_name, is_weekend)` for the window — derived factual data, generated by script | Facts, no license |
| TLC reference codes | TLC data dictionary | `rate_codes`, `payment_types` lookup tables | NYC Open Data |

Schema detail (column types via the canonical type system, per-dialect DDL) is
an implementation artifact under `scripts/sample-data/`, not spec text.

**MySQL driver caveat (real constraint, stated up front):** Connector/J does
not ship in the core image (GPL + FOSS exception — datasources §4.1, §10).
The demo server and any org wanting the MySQL sample must build with
`-Pmysql` or drop the driver jar into `lib/`. The quickstart documents this
as one copy-paste line; the loader script fails with a clear message — not a
stack trace — when the dialect is unavailable.

## 4. Artifact layout & manifest

```
s3://<bucket>/sample-data/mobility/v1/
    manifest.json
    pg-trips.dump          # pg_dump -Fc of dp_sample_trips
    mysql-weather.sql.gz   # mysqldump
    nyc_reference.db       # SQLite file, used as-is
    examples.json          # D9 pipelines + templates, workspace-relative
```

`manifest.json` (schema_version 1): artifact list with `file`, `engine`,
`sha256`, `bytes`, `restore_hint`; dataset provenance list with `source_url`,
`retrieved_at`, `transform` (one line), `row_count`, `license`,
`license_verified` (date or `null` — **publishing with any `null` blocks
go-live**, §8). Version directories are immutable: any change, even a typo,
is `v2`. Consumers pin the version; nothing references a `latest` alias.

Build tooling lives in the product repo at `scripts/sample-data/`
(`set -euo pipefail` throughout). The scripts are the source of truth; S3
holds build outputs.

### 4.1 Build pipeline (download → transform → dump)

Stages, each a separate script so a failed stage re-runs alone:

1. **`download.sh`** — fetch the pinned source files (exact URLs in a
   `sources.lock` file: TLC monthly Parquet files for the pinned 24-month
   window, NOAA GHCN-Daily per-station `.csv` for the pinned station IDs,
   TLC zone lookup CSV) into `work/raw/`. **Each source file's SHA-256 is
   pinned in `sources.lock` and verified after download** — upstream files
   can be silently re-published; a drifted input fails the build loudly
   (re-pinning is a deliberate, reviewed change), which is what makes D8's
   "rebuildable artifact" claim true against moving sources.
2. **`transform.sh`** — the ETL workhorse is the **DuckDB CLI**, pinned and
   SHA-verified into `.tools/` exactly like the scanner binaries (the F1
   pattern from run 009; DuckDB was dropped as a *runtime* dialect, D2 —
   as a *build* tool it is the natural choice: it reads TLC's Parquet
   natively and emits CSV). Per dataset: trips — filter the window,
   hash-sample (`hash(trip columns) % k = 0`, no RNG) to the ~5M target,
   normalize codes, emit `trips.csv` + monthly-summary CSVs; weather —
   melt GHCN element rows to an observations table + stations; reference —
   zones/rate-code/payment-type CSVs passed through; calendar — generated
   by script from the pinned holiday list.
3. **`load-and-dump.sh`** — create the target schemas (per-engine DDL files
   in `scripts/sample-data/ddl/`, column types per the canonical type
   system), bulk-load the CSVs into throwaway local engines (`psql \copy`,
   `mysql LOAD DATA LOCAL INFILE`, `sqlite3 .import`), assert the row
   counts recorded in the manifest, then dump: `pg_dump -Fc`, `mysqldump
   | gzip`, and ship the SQLite file itself.
4. **`manifest.sh`** — compute artifact SHA-256s, assemble `manifest.json`
   (§4), embedding the source pins and row counts from stages 1–3.

Determinism contract: same `sources.lock` + same scripts ⇒ byte-stable
table *contents* (dump bytes may differ across tool versions; the manifest
therefore records row counts and per-table content checksums computed IN
the engine — `SELECT count(*)`, ordered aggregate hash — and those, not
dump-file bytes, are what the E2E test asserts).

## 5. Loading (deployment side)

`deploy/sample-data/load.sh <base-url> <version>` — curl each artifact,
verify SHA-256 against the manifest (hard fail on mismatch), then per engine:

- **PG:** `pg_restore` into a `dp_sample_trips` database — on the demo/eval
  compose, a second database on the existing Postgres container (no new
  service); orgs may point it anywhere.
- **MySQL:** restore into a `dp_sample_weather` database on the compose's
  `mysql` service (new, demo/eval profile only).
- **SQLite:** place `nyc_reference.db` on a volume mounted read-only into
  the app container (e.g. `/data/sample/`).

Compose integration: a `sample-data` one-shot service (profile `demo`) runs
the loader before the app starts; idempotent (skips engines whose marker
table `_sample_meta(version)` already matches). After load, the PG/MySQL
loader creates the **SELECT-only demo login** (`dp_demo_ro`) the registered
datasources use — read-only credentials are created by the loader, not
assumed (spec 1 D6 layer 3).

## 6. Bootstrap datasource registration (app side, generic feature)

Proposed config key (configuration.md at implementation):
`datapipelines.bootstrap.datasources-file` — path to a YAML file of
datasource definitions (D6). **Unset = feature off** (the only
enable/disable switch); set-but-unreadable or unparseable = fail-fast
startup. Env derivation per configuration §1
(`DATAPIPELINES_BOOTSTRAP_DATASOURCES_FILE`), so the demo compose mounts the
file and points one env var at it. File shape — the same field vocabulary as
`POST /api/v1/datasources` (datasources §3.1) plus the spec-1 flags, with
`${ENV_VAR}` placeholders resolved against the app's environment (secrets
never live in the file):

```yaml
# /etc/datapipelines/bootstrap-datasources.yml
datasources:
  - name: sample-trips
    display_name: "NYC Taxi Trips (sample)"
    dialect: POSTGRES
    jdbc_url: jdbc:postgresql://postgres:5432/dp_sample_trips
    username: dp_demo_ro
    password: ${SAMPLE_PG_PASSWORD}
    readonly: true
    global: true
  # sample-weather (MYSQL), sample-reference (SQLITE, read-only open mode) alike
```

Semantics: applied once per startup, after Flyway and after §6.1 actor
resolution, before serving traffic. **Create-if-absent by `name`; an
existing row (even soft-deleted) is left untouched** and logged at INFO.
Entries run the full datasources §9 validation (test pool build included); a
failing entry fail-fasts startup — a half-registered demo is worse than a
loud one. The SQLite entry sets the xerial read-only open property via
`properties.jdbc` (exact key verified against the pinned driver at
implementation — not recalled here).

### 6.1 Bootstrap actor (D7 as ratified)

`created_by` for every bootstrap-registered datasource is the user
identified by the **existing** `datapipelines.auth.bootstrap-admin-email`
key (auth §4.4) — a valid OIDC account (Google/Microsoft on the public
demo). Startup ordering when `datasources-file` is set:

1. `bootstrap-admin-email` unset → **fail-fast** with a config error naming
   both keys (registration needs an actor; silence here would be a broken
   FK at runtime).
2. If no `users` row exists for that email (lowercase-normalized, per auth
   §4.2), **pre-provision** one: `email` = the configured value,
   `display_name` derived from the email local-part, `provider =
   'bootstrap'` / `provider_subject` = the email (placeholder values
   satisfying the NOT NULL columns and the `(provider, provider_subject)`
   unique index), `is_active = TRUE`, `is_admin = TRUE` — this **is** the
   auth §4.4 bootstrap-admin grant firing at row creation, moved earlier in
   time; the `auth.user.admin_granted` audit event (actor `bootstrap`) is
   emitted here, exactly once.
3. At that admin's first real OIDC login, the existing email-keyed linking
   flow (auth §4.2 step 2) replaces the placeholders with the true OIDC
   identity. **One amendment to that flow is required:** §4.2 today updates
   `provider`, `provider_subject`, `last_login_at`, `profile_picture_url` —
   but not `display_name`, so the placeholder name would stick forever.
   When the linked row still carries `provider = 'bootstrap'` (i.e., this
   login is completing a pre-provisioned identity), the update also sets
   `display_name` from the ID token's `name` claim. Ordinary re-logins keep
   today's behavior (a user's chosen display name is not clobbered on every
   login). §4.4's "grant fires only at row creation" rule is respected: the
   login updates identity fields and grants nothing.

auth.md amendment at implementation: §4.4 gains this pre-provisioning
paragraph (grant-at-creation semantics unchanged); §4.2's linking step is
referenced, not modified.

Example-content seeding (D9): `datapipelines.bootstrap.examples-url` (or a
baked file path) — when set and provisioning-mode is `auto-per-user`, the
workspace-provisioning hook imports `examples.json` into the new personal
workspace via the existing import path (full §12 validation applies).

## 7. Demo-deployment security posture (datapipelines.co)

- Seeded datasources: `global` + `readonly` + SELECT-only DB users (all
  three layers of spec 1 §6).
- `datapipelines.workspaces.member-datasources-enabled=false` — open
  datasource creation from a public server is an SSRF/port-scan primitive;
  demo users get the seeded datasources only. Additionally the demo network
  egress-restricts the app container (deployment.md hardening checklist
  amendment).
- Open provisioning stays on (that's the product being demoed) but
  `auto-per-user` + workspace isolation bounds blast radius; existing global
  rate limits apply. Per-workspace quotas and personal-workspace TTL cleanup
  are pre-launch operational items (spec 1 §11 carries them; revisit before
  the domain goes live).
- Nightly artifact re-restore is **not** required (nothing writable exists)
  but the loader's idempotence makes it a one-line cron if wanted.

## 8. License verification — go-live gate

Every license cell in §3 is a research claim. Before datapipelines.co serves
this data publicly: verify each source's current terms, record the date in
`manifest.json.license_verified`, and keep the evidence links in the build
script comments. A dataset that fails verification is swapped, not shipped.
(Known-rejected on license grounds, do not substitute in: IMDb, MovieLens,
Kaggle Olist, Instacart — all non-commercial.)

## 9. Testing

- **Build:** manifest checksums recomputed post-build must match; row counts
  in manifest asserted against the loaded throwaway engines.
- **Loader:** corrupted-artifact download fails loudly (checksum test);
  re-run on a loaded target is a no-op (marker table).
- **App:** bootstrap registration integration tests — fresh DB registers
  all entries; restart mutates nothing; operator-edited row untouched;
  invalid entry fail-fasts; `datasources-file` set without
  `bootstrap-admin-email` fail-fasts; admin row pre-provisioned with
  placeholder provider fields + `is_admin`, recorded as `created_by`, and a
  subsequent OIDC login for that email links identity (incl. `display_name`
  from the ID token — the §6.1 bootstrap-completion case) without
  re-granting; a later ordinary re-login does not overwrite `display_name`.
- **E2E (integration-tests):** load the real artifacts into containers,
  register, then execute one seeded example pipeline end-to-end — a
  cross-datasource join (PG trips × SQLite zones staged in tempdb) asserting
  known-good aggregate values pinned from the build.
- **docs-audit** green after every doc amendment (standing rule).

## 10. Explicitly out of scope (deferred)

- DuckDB sample + Parquet distribution (D2; returns with the analytics story).
- Multiple artifact sizes (small/full); v1 ships one (~5M-row trips).
- Automated nightly reset; per-workspace quotas (spec 1 §11).
- Exposing cooked results as dedicated endpoints (owner's stated *later*
  goal — separate design when reached).
- Oracle / MSSQL / H2 sample databases (H2 is staging, not a demo target).
- CloudFront / requester-pays / GH-Releases mirroring decisions (D4 note) —
  pure ops, decide when traffic exists.
