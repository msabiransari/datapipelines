# Design: Warehouse and Lake Connectors — Snowflake first, the AWS lake through DuckDB second

**Status:** design note, researched 2026-09-05; every fact below carries its source and the
date it was read. Items the sources did not settle are listed in §7 as spike work — they are
NOT design decisions. Awaiting the owner's ruling on §6.
**Amended 2026-09-07 (round 087, connector seams).** Three corrections, marked inline:
§2 gains Databricks as a fourth reference target (F14–F17, read 2026-09-07); §3.1's row-cap
sentence said "LIMIT/OFFSET", which the adapter has never modelled; and §4's Round B was
**impossible as written** against the shipped DuckDB adapter — it now names the lake-mode
adapter 087 built, and says why.
**Not packaged into the product** (`docs/superpowers/` is excluded from the jar).

## 1. Why

- Search demand (DataForSEO Labs, US, 2026-09-04, six-month history): `snowflake mcp server`
  **1,000/mo** (720 → 1,300), `databricks mcp server` 590, `bigquery mcp server` 210 — the first is
  larger than every engine we support except Postgres (`postgres mcp server` 720). `athena`,
  `glue`, `redshift` "mcp server" terms: no data. `duckdb iceberg` 260/mo and rising.
- Organisations whose tables are native Snowflake can reach them **only** through Snowflake's
  engine: the storage is Snowflake's own S3, in its proprietary micro-partition format. A
  connector is for the data, the compute pushdown and the governance they already configured
  — not for metadata.
- Organisations whose lake is **open-format on their own S3** (Parquet/Iceberg, catalogued by
  Glue or S3 Tables) can be read by DuckDB — an engine we already ship — with no per-query bill.

Two rounds, in that order. Both are post-announcement.

## 2. Verified facts (source · read 2026-09-05)

| # | Fact | Source |
|---|---|---|
| F1 | `net.snowflake:snowflake-jdbc` current release **4.3.4**, published 2026-09-03; licence **Apache-2.0** — may ship in the image | Maven Central `maven-metadata.xml` + the 4.3.4 POM |
| F2 | 4.0.0 (2026-01-27) restructured the public API: driver class is `net.snowflake.client.api.driver.SnowflakeDriver`; internals under `net.snowflake.client.internal.*`; AWS SDK v2; the BouncyCastle JVM flag renamed to `net.snowflake.jdbc.useBundledBouncyCastleForPrivateKeyDecryption` | docs.snowflake.com JDBC release notes 2026 |
| F3 | Driver README states Java 1.8+; the repo builds on **Java 21** (`buildSrc`, `JavaLanguageVersion.of(21)`) — no conflict | GitHub README; `buildSrc/build.gradle.kts:38` |
| F4 | **Single-factor password deprecation, Phase 3 = Aug–Oct 2026 (now): "all non-human users are blocked from using a password to authenticate"; `LEGACY_SERVICE` fully deprecated.** New non-human users since Phase 2 (May–Jul 2026) must be `TYPE=SERVICE`, "which prevents them from using a password" | docs.snowflake.com `security-mfa-rollout` |
| F5 | **Programmatic Access Tokens (PAT) are accepted in the driver's password slot**: "you can specify the token for the value of the password in the driver settings"; available to `TYPE=SERVICE` users, who "must specify the role that will be used during sessions authenticated with that token"; default lifetime 15 days, **max 365 days**; max 15 tokens per user; service users need a **network policy** assigned | docs.snowflake.com `programmatic-access-tokens` |
| F6 | Key-pair auth in JDBC: `private_key_file` + `private_key_file_pwd` (or a `privateKey` object in `Properties`); RSA **2048-bit minimum**; BouncyCastle needed for encrypted PKCS#8 keys | docs.snowflake.com `jdbc-configure` |
| F7 | JDBC URL `jdbc:snowflake://<account_identifier>.snowflakecomputing.com/?warehouse=…&db=…&schema=…&role=…` | same |
| F8 | `SNOWFLAKE_SAMPLE_DATA` (TPC-H, TPC-DS) is shared into every account from `SFC_SAMPLES`; "do not use any data storage so they do not incur storage charges"; querying needs a running warehouse | docs.snowflake.com `sample-data` |
| F9 | Trial: 30 days from sign-up or the free balance ($400 per snowflake.com/snowflake-trial), whichever first; at the end **the account is suspended, not deleted** — data stays, nothing runs; a card converts it to paid at any time; trial accounts lack external network access, hybrid tables, Openflow, Duo MFA | docs.snowflake.com `admin-trial-account`; snowflake.com |
| F10 | `org.duckdb:duckdb_jdbc` current release **1.5.5.1** (2026-08-03) — exactly what the repo pins | Maven Central; `gradle/libs.versions.toml:97` |
| F11 | DuckDB `iceberg` extension attaches **Amazon S3 Tables** (`ATTACH '<arn>' … (TYPE iceberg, ENDPOINT_TYPE s3_tables)`) and **AWS Glue** (`ATTACH '<account_id>' … (TYPE ICEBERG, ENDPOINT_TYPE 'glue')`), plus Polaris, Lakekeeper, R2 and any Iceberg REST catalog; requires `httpfs`, `iceberg`, `aws`; credentials via `CREATE SECRET (TYPE s3, PROVIDER credential_chain)` or explicit `KEY_ID`/`SECRET`; catalog-attached tables support writes | duckdb.org `core_extensions/iceberg/catalogs` and `overview` |
| F12 | The product refuses secret-valued keys in `properties.jdbc` (stored plaintext) and credentials in the URL; `username`/`password` are the only credential carriers, encrypted (068: versioned AES-GCM under a `KeyProvider`) | datasources.md §5.6, §7.1 |
| F14 | **Databricks ships TWO drivers under the same Maven coordinates, under DIFFERENT licences.** `com.databricks:databricks-jdbc` **3.4.2** (latest release; `maven-metadata.xml` `lastUpdated` 20260720105516) declares **Apache-2.0** in its POM and points at github.com/databricks/databricks-jdbc — the OSS driver. The SAME coordinates at **2.7.5** declare "**Databricks JDBC Driver License**" (databricks.com/jdbc-odbc-driver-license) — the Simba-based legacy driver. **A version range or a `+` would silently change the licence**, which is exactly why this repo pins exact versions (MISTAKES.md). Ships-in-the-image is a 3.x-only question. | Maven Central `maven-metadata.xml` + the 3.4.2 and 2.7.5 POMs, read 2026-09-07 |
| F15 | PAT auth: `AuthMech=3;UID=token;PWD=<token>` — `UID` is the LITERAL string `token`, the PAT goes in `PWD`. OAuth M2M: `AuthMech=11;Auth_Flow=1;OAuth2ClientId=…;OAuth2Secret=…`; token passthrough is `Auth_Flow=0;Auth_AccessToken=…`. Connection URL: `jdbc:databricks://<host>:443;transportMode=http;ssl=1;AuthMech=3;httpPath=<path>` | github.com/databricks/databricks-jdbc README + docs.databricks.com `integrations/jdbc-oss/authentication`, read 2026-09-07 |
| F16 | Unity Catalog objects "follow a three-level namespace (`catalog.schema.object`)"; `ConnCatalog`/`ConnSchema` set the connection's default catalog and schema (default catalog `hive_metastore`) | docs.databricks.com `data-governance/unity-catalog/`, read 2026-09-07 |
| F17 | `Auth_AccessToken` and BigQuery's `OAuthPvtKey` / `OAuthAccessToken` / `OAuthRefreshToken` are secret-valued property names that end in neither `password`/`pwd`/`secret`/`clientkey` — the product's §5.6 suffix predicate misses all four. 087 enumerates them (`SECRET_VALUED_KEYS`) rather than widening the suffixes. BigQuery's driver is `com.google.cloud:google-cloud-bigquery-jdbc` | docs.cloud.google.com `bigquery/docs/jdbc-for-bigquery` + the Databricks README, read 2026-09-07 |
| F13 | The Athena JDBC driver is **not on Maven Central** (`com/amazon/athena/` absent) — AWS distributes it from its own site; no Athena/Glue/Redshift MCP search demand measured | Maven Central listing; DataForSEO 2026-09-05 |

## 3. Round A — Snowflake (dialect + PAT credential, no schema change)

**The finding that shapes it:** with F4 in force, a JDBC service login with a plain password no
longer works. With F5, a **PAT in our existing `password` field** does. So v1 needs **no new
credential kind**: the datasource carries `username` (a `TYPE=SERVICE` user) and `password` (its
PAT), encrypted exactly as today; the Snowflake role is fixed on the token.

Scope:
1. `Dialect.SNOWFLAKE` (enums.md catalogue, drift-tested), the adapter per datasources.md §4.2
   (identifier quoting, a **row cap** — `RowLimitStyle`, the adapter models a cap and nothing
   else; **corrected 2026-09-07: this line said "LIMIT/OFFSET" and OFFSET is not a thing here**,
   `applyRowLimit` caps a module-built preview SELECT and never paginates — `information_schema`
   introspection: Snowflake's is ANSI, but VERIFY the column-metadata shapes in the spike), the
   driver **4.3.4** pinned in `libs.versions.toml`, shipped in the image (Apache-2.0, F1), with
   F2's driver class name. Its `NamespaceShape` is `[database, schema]` with TWO browsable
   levels (087, datasources.md §4.2), and its credential is `credential.kind: token` — the PAT
   in the password slot — with `private_key` as the A2 kind, both already in the contract.
2. **Type mapping — measured, not asserted** (§7.1). Snowflake `NUMBER(38,0)` is its integer
   default; `VARIANT`/`OBJECT`/`ARRAY` come back as strings in JDBC; the three `TIMESTAMP_*`
   variants differ in zone semantics. The spike prints `getColumnTypeName`/`getColumnType` for
   every type over `SNOWFLAKE_SAMPLE_DATA` and the mapper is written from that table.
3. **Read-only is a Snowflake ROLE, not a pool flag.** Hikari's `readOnly` means nothing to
   Snowflake; datasources.md §5.7's three layers apply with layer 3 rewritten for this dialect:
   the documented pattern is a `TYPE=SERVICE` user granted a read-only role, and the `readonly`
   flag on the datasource still enforces layers 1–2 (contract + runtime check).
4. **Pool posture:** small `maximumPoolSize` (sessions hold a warehouse), `warehouse` in the
   URL, and a doc note that the customer's warehouse should `AUTO_SUSPEND` (60 s is the
   conventional floor) — every idle second of a running warehouse is billed to them.
5. **PAT lifetime is the operator's job (F5):** max 365 days, so the doc says rotate before
   expiry and the product already supports a password change without restart (050 pool
   invalidation). A `last_test_outcome` (V9) of "authentication failed" is the symptom of an
   expired PAT — name it in the docs.
6. **Tests:** no Testcontainer exists for Snowflake. The dialect parity suite runs against a
   real account behind `DATAPIPELINES_TEST_SNOWFLAKE_*` env (skipped when absent — say so
   loudly in the report, never silently green); target `SNOWFLAKE_SAMPLE_DATA.TPCH_SF1`, an
   `X-Small` warehouse with `AUTO_SUSPEND = 60`. **The trial is for building, not for CI** (F9:
   suspended at 30 days → tests would go red); a permanent account converted from the trial is
   the guard's home — a suspended warehouse bills nothing, so the running cost is the storage
   minimum plus test minutes.
7. **Demo without publishing anything:** a seeded pipeline over `SNOWFLAKE_SAMPLE_DATA` (F8: no
   storage cost, no licence question — it is Snowflake's own shared sample) joined through
   tempdb to `nyc_reference.db` — a real cross-engine showcase. It seeds only when a Snowflake
   datasource is configured (bootstrap YAML, optional).
8. **Site:** `/mcp-server/snowflake` on the 073 engine template the day the dialect ships.

**Deferred to Round A2 — key-pair auth (F6):** a second credential kind, `private_key` (PEM,
optionally passphrase-protected), encrypted through the 068 path. **The SEAM shipped in 087**:
`credential.kind ∈ {password, token, private_key, service_account_json, none}` is live on
`POST /api/v1/datasources`, `datasources_create`, the bootstrap file and the datasource form,
and `private_key` is refused today only because no shipped adapter declares it in
`supportedCredentialKinds`. Round A2 is now one line in the Snowflake adapter plus its
`applyCredential` override — and the same seam carries BigQuery (service-account JSON) and
Databricks (OAuth M2M, F15) with no further contract change.

## 4. Round B — the AWS lake through DuckDB (Parquet / Iceberg on the customer's S3)

> **Correction, 2026-09-07 (087).** As originally written this round was **impossible against the
> shipped `DUCKDB` adapter**, and the note did not say so. That adapter sets
> `enable_external_access = false` in `defaultProperties`, and DuckDB **runtime-locks** the
> setting — it cannot be turned back on once the database is running. With it on there is no
> filesystem and no network, so `INSTALL`, `LOAD`, `ATTACH`, `read_parquet` and `CREATE SECRET`
> all fail (verified 2026-09-07 against duckdb_jdbc 1.5.5.1: `ATTACH` of a local file fails with
> *"Permission Error: … file system operations are disabled by configuration"*). That lock is
> the load-bearing control that stops author SQL loading native code inside the app's own
> process, and it STAYS.
>
> **Round B therefore runs on the lake-mode adapter, which 087 shipped**: `Dialect.LAKE`, a
> distinct dialect rather than a mode on `DUCKDB` (a mode would make the §5.6 refusal set a
> function of row data, which the enum-total lookup exists to prevent). It omits
> `enable_external_access` so the engine default applies, keeps `allow_unsigned_extensions` and
> `allow_community_extensions` at `false`, refuses the same key set in `properties.jdbc`, and
> generates its setup block from typed `properties.dialect.*` fields through
> `DialectAdapter.connectionInit` (datasources.md §4.2A). What is left for this round is the
> lake CONNECTOR — the registry, the query path, the S3 proof — not the adapter seam.

- A "lake" datasource is a `Dialect.LAKE` datasource (DuckDB underneath, file or `:memory:`)
  whose connections run a setup block: `INSTALL/LOAD httpfs, aws, iceberg; CREATE SECRET (TYPE
  s3, PROVIDER credential_chain); ATTACH … ENDPOINT_TYPE 'glue' | s3_tables` (F11). Pipelines
  then query the attached catalog's tables like any DuckDB table; results stage into tempdb as
  today. Its `NamespaceShape` is `[catalog, schema]` with two browsable levels (087).
- **Credentials: the pod's IAM role, not keys in our database.** `credential_chain` (F11) reads
  the instance/task role — the self-hosted answer with nothing to encrypt or rotate. Explicit
  `KEY_ID`/`SECRET` would have to travel through an encrypted credential kind (§3 A2), because
  F12 forbids secrets in `properties.jdbc`. v1 = role only.
- **Extensions are downloaded at runtime** from DuckDB's extension repository unless bundled —
  an egress requirement and an air-gap problem; the round must either bundle the three
  extensions into the image or document the egress. Decide in the spike (§7.3).
- The setup block is per-connection SQL; it must NOT be user-supplied free text (that is a SQL
  surface inside the app's own process). It is generated from typed datasource fields
  (`catalog.kind = s3 | glue | s3_tables | rest`, `catalog.ref`, `region`, `endpoint`,
  `url_style`, `attach`) — the same "config, not code" rule as everything else here. **Shipped in
  087** as `properties.dialect.*` + `LakeDialectAdapter.connectionInit`, with the local (ATTACH)
  path proven end to end and the S3 path proven only at the level of the SQL generated.
- Athena as a dialect (F13: driver off-Maven, no measured demand) only when a customer asks.

## 4A. Databricks — a reference target, not a round (added 2026-09-07)

Not scoped as a round here; it is the fourth target the 087 contract was designed against, so
its shape is recorded while the facts are fresh.

- **Driver:** `com.databricks:databricks-jdbc` 3.4.2, **Apache-2.0** — may ship in the image.
  Pin the exact version and never a range: 2.x under the same coordinates is the Simba driver
  under a proprietary Databricks licence (F14). A licence audit that reads only the coordinates
  would get the wrong answer.
- **Credential:** `credential.kind: token` — the adapter puts the PAT where the driver wants it
  (`UID=token`, the PAT in `PWD`, `AuthMech=3`), which is exactly the case `applyCredential`
  exists for: the CALLER says "token", not "`PWD`" (F15). OAuth M2M is a later kind or a
  `dialect.*`-configured flow (`OAuth2ClientId` + `OAuth2Secret`, the latter already caught by
  the §5.6 suffix predicate).
- **Namespace:** `[catalog, schema]`, two browsable levels — Unity Catalog's three-level
  `catalog.schema.object` (F16). `ConnCatalog`/`ConnSchema` set the connection default, which is
  the same "outer level fixed by the connection unless the driver lists them" question every
  two-level dialect answers.
- **Typed config:** `dialect.http_path` (required — the SQL warehouse's HTTP path) and
  `dialect.catalog`.

## 5. Cost of a wrong guess, named

- Round A without F4/F5 would have shipped a password-only connector that cannot log in to any
  service user created after July 2026 — a demo that fails on first contact.
- Round B with keys in `properties.jdbc` would have put S3 credentials in a plaintext column.

## 6. Rulings needed (owner)

- R-WH1: proceed with Round A as scoped (PAT in v1, key-pair as A2)?
- R-WH2: a permanent Snowflake account for the parity suite after the trial — yes/no?
- R-WH3: Round B after Round A, or only when a customer asks?

## 7. Spike work — measure, do not assume

1. **Type map**: `getColumnType`/`getColumnTypeName`/`getObject().javaClass` for every Snowflake
   type over `SNOWFLAKE_SAMPLE_DATA` + a scratch table with `VARIANT`/`OBJECT`/`ARRAY`/all three
   `TIMESTAMP_*` — the mapper is written from the printout.
2. **Introspection**: `DatabaseMetaData.getTables/getColumns` vs `information_schema` for a
   shared database — which one the adapter should use (shared databases behave differently).
3. **DuckDB extensions from the JDBC driver**: does `duckdb_jdbc` 1.5.5.1 `INSTALL`/`LOAD`
   `httpfs`/`aws`/`iceberg` in the app's container; egress needed; size added if bundled.
4. **PAT + network policy** end-to-end from the app's container: the policy must allow the
   deployment's egress IP — document the operator step.
