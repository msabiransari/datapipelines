# Datasources Specification

**Status:** v2.24 (frozen contract — additive-only changes after this point)
**Owner:** datapipelines.co core
**Depends on:** [Type System spec](type-system.md) · [Enums](enums.md) · [Configuration](configuration.md) · [Metadata DB](metadata-db.md) · [Pipeline Contract](pipeline-contract.md)
**Last updated:** 2026-09-09

---

## 1. Purpose

A **Datasource** is the environment-specific connection to an external database. Pipelines reference datasources by **stable name** (e.g., `pg-prod`); the Datasource Registry resolves that name to actual connection details (JDBC URL, credentials, pool size) per environment.

This separation is what makes **pipelines portable across environments**: the same pipeline JSON runs in dev, staging, and prod, with each environment's Datasource Registry providing different actual connections for the same names.

This spec defines:
- The Datasource entity model.
- Per-dialect JDBC adapter behavior.
- The Datasource Registry API.
- Connection pool configuration and lifecycle.
- Credential storage (encryption at rest).
- Connection testing and health checks.

---

## 2. Design Principles

1. **Name-stable, env-resolved.** Datasource names are the contract between pipelines and connections. Names like `pg-prod` are stable across envs; their underlying JDBC URLs differ.
2. **Credentials never in pipeline JSON.** Pipelines reference names only. Datasource credentials live in the Datasource Registry, encrypted at rest, never returned in GET responses.
3. **One dialect per datasource.** The `dialect` declares the JDBC driver, type mapper, SQL behavior. Pipelines validate at write time that their template's `dialect` matches the referenced datasource's `dialect`. The `Dialect` value set has a single authority: [Type System §5](type-system.md#5-source-to-canonical-mapping-tables) (mirrored, non-normatively, by [Enums §5](enums.md#5-dialect--supported-source-database-dialects)). This spec is a *consumer* of that list — §4.1 below maps each value to its driver, it does not define the set.
4. **Pooled connections, not per-query.** Each datasource has its own HikariCP connection pool. Pipeline node executions lease connections from the pool and return them.
5. **Health-checkable.** Every datasource can be tested via `POST /api/v1/datasources/{name}/test`. Pipeline execution pre-checks that all referenced datasources are reachable.
6. **Fail loudly on missing datasource.** A pipeline referencing a datasource name not in the registry fails validation at write time (`pipeline.validation.unknown_datasource`). A datasource removed at runtime causes execution to fail (`pipeline.node.datasource_not_found`).
7. **Validate on write, universally.** No invalid datasource ever reaches the database. Every create/update runs the full §9 rule set *plus* a **test pool build** (§5.4) before the row is written — the same cross-cutting principle as [Pipeline Contract §2, principle 8](pipeline-contract.md#2-design-principles). Connection *properties* are therefore validated by HikariCP and the driver themselves, not by an allowlist maintained in this doc.
8. **Passthrough over allowlist.** Pool and driver tuning is expressed as two namespaced passthrough maps (`properties.hikari.*`, `properties.jdbc.*`). Every HikariCP property and every driver connection property is reachable without a spec change; correctness is enforced at pool build, not by enumeration.

---

## 3. Datasource Entity

### 3.1 JSON structure (request — `POST /api/v1/datasources`)

```json
{
  "name": "pg-prod",
  "display_name": "Production Postgres",
  "description": "Primary OLTP database.",
  "dialect": "POSTGRES",
  "jdbc_url": "jdbc:postgresql://pg-prod.internal:5432/app_db",
  "credential": {                          // §3.4 — WHAT the credential is
    "kind": "password",                    // password | token | private_key | service_account_json | none
    "username": "datapipelines_app",
    "secret": "..."                        // write-only; never returned in GET
  },
  "query_timeout_seconds": 60,
  "global": false,                         // OPTIONAL (workspaces D8) — admin-only; true = shared
                                           // infrastructure (workspace_id NULL)
  "workspace": "team-etl",                 // OPTIONAL (workspaces D8) — a workspace the caller can
                                           // access; default = the ACTIVE workspace
  "readonly": true,                        // OPTIONAL (§5.7) — forbids the three write-shaped uses
  "introspection_include_schemas": ["apex_reporting"],   // OPTIONAL — §7A escape hatch for the
                                                          // system-schema exclusion floors
  "properties": {
    "hikari": {
      "maximumPoolSize": 10,
      "minimumIdle": 2,
      "connectionTimeout": 30000,
      "idleTimeout": 600000,
      "maxLifetime": 1800000
    },
    "jdbc": {
      "ssl": "true",
      "sslmode": "verify-full",
      "sslrootcert": "/etc/ssl/certs/pg-prod-ca.pem",
      "ApplicationName": "datapipelines"
    }
  }
}
```

The **legacy credential pair** — top-level `"username"` and `"password"` — is still accepted and means `credential: {kind: "password", username, secret}`. It is frozen in v1 (§12.1): every client, bootstrap file and example written before the `credential` block keeps working. A payload carrying BOTH is refused (`datasource.validation.properties_invalid`) rather than resolved by precedence — the two can disagree and there is no defensible winner.

`properties` has exactly two reserved namespaces — `hikari` (pool properties, applied verbatim to `HikariConfig`) and `jdbc` (driver connection properties). Both are optional, both default to `{}`, and neither is allowlisted by this spec. See §5.

### 3.2 JSON structure (response — `GET /api/v1/datasources/{name}`)

Identical to request, except:
- The credential **secret** is **never** returned — neither as `password` nor as `credential.secret`. The response carries `credential: {kind, username?}` and `password_set: true | false`, which is DERIVED from the kind: V13's `chk_datasource_credential_present` makes `credential_kind = 'none'` and "no stored ciphertext" the same fact, so `password_set` is `false` exactly for `kind: "none"`.
- Top-level `username` is still returned (frozen shape) and is `null` for the kinds that have none.
- `jdbc_url` is included (operators need it for debugging).
- `workspace` (string, additive — workspaces design §9): the bound workspace's NAME, `null` = global.
- `readonly` (boolean, additive): the §5.7 flag, machine-readable.
- `last_test` (object or `null`, additive — §8.1B): the outcome of the LAST connection test —
  `{"tested_at": "2026-08-30T09:15:00Z", "ok": false, "message": "FATAL: password authentication failed …"}`.
  `null` means never tested, which is what every datasource registered before this field existed
  is. Never supplied on write: it is an observation, recorded by the probe.
- `properties.dialect` (additive, 109 §B): the row's dialect configuration, **non-secret keys
  only**, filtered through the SAME §5.6 classification that validates `properties.jdbc` (the
  union + the secret-valued suffix predicate — a secret-named key can never be stored under one
  carrier and shown under another). The filtering is over KEYS; values pass through untouched,
  because a value that is itself a secret belongs under `credential`, which never reaches
  properties at all. The shown/hidden contract, per dialect:

  | Dialect | Key | Shown? | Why |
  |---|---|---|---|
  | LAKE | `catalog.kind` | shown | which object-store catalog backs Iceberg tables — an operator debugging a REST-catalog refusal needs to see it |
  | LAKE | `catalog.ref` | shown | the SSRF allowed-root marker (a `file://` mirror root or a REST catalog URL); never a credential — its grammar admits no secret |
  | LAKE | `region`, `endpoint`, `url_style` | shown | addressing; an operator debugging signature/region mismatches needs all three |
  | LAKE | `unsigned` | shown | whether reads skip the credential chain — the demo bucket's public-read posture |
  | LAKE | `memory_limit`, `threads`, `temp_directory` | shown | the §8C.4 engine limits the row actually runs with |
  | LAKE | `attach` | shown | extra catalogs to attach at connect |
  | any | a key the §5.6 classification refuses (`password`-suffixed, `access_key`-shaped, the `SECRET_VALUED_KEYS` set, `CREDENTIALS`, `SERVER_MANAGED`) | **hidden** | defense in depth: no such key is accepted under `dialect` today, and the filter exists so a future key cannot reopen the hole |
  | JDBC dialects | `properties.hikari.*` (the §5 pool tunables) | shown via the existing `pool` object | the pool's EFFECTIVE settings (value + unit + source layer), which is what an operator reads; the raw stored `properties.hikari` map has been on the wire since 094 |

  A declared dialect property whose value is empty, whitespace-only or null is REFUSED at
  register/update with `datasource.validation.property_empty` (§13.8) — an empty string is never
  a configuration. At bootstrap a field whose whole value is one `${VAR}` that resolves
  SET-BUT-EMPTY is OMITTED (the operator's off-switch — the shipped `catalog.ref:` placeholder
  stays empty in production and means "not declared"); a literal `""` in a file fails the boot
  with the key named.

### 3.3 Field reference

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Stable identifier and primary key. `[a-z0-9_-]+`, length 1–63. **Immutable** — see §11.1. |
| `display_name` | string | yes | Human-readable name. |
| `description` | string | **optional** | Long-form description. Absent or empty is legal; nothing in the system requires it. |
| `dialect` | string (enum) | yes | A `Dialect` value — authority is [Type System §5](type-system.md#5-source-to-canonical-mapping-tables) ([Enums §5](enums.md#5-dialect--supported-source-database-dialects)). Determines the JDBC driver, type mapper, and SQL behavior. |
| `jdbc_url` | string | yes | JDBC URL for the dialect. |
| `credential` | object | yes on create (or the legacy pair) | `{kind, username?, secret?}` — see §3.4. |
| `username` | string | legacy / derived | DB username. Required only for `credential.kind: password`; `null` for the kinds that have none. Accepted at top level as the legacy shape, returned at top level always. |
| `password` | string | legacy, never returned | The legacy spelling of `credential.secret`, meaning `kind: password`. |
| `query_timeout_seconds` | integer | optional | `Statement.setQueryTimeout` for every node executing against this datasource. When set, it **overrides** `datapipelines.executor.node-query-timeout-seconds` — see §5.5. |
| `introspection_include_schemas` | array of strings | optional | §7A escape hatch for the dialect's system-schema exclusion: a schema named here is exempt from the exclusion in **all three** introspection operations. Exact names over the **legal-identifier alphabet of the supported dialects** — letters, digits, `_`, `$`, `#` (`_` is an ordinary name character, not SQL-LIKE's wildcard here; an entry outside the alphabet is rejected at save with `datasource.validation.properties_invalid`, because wildcards, quoted identifiers, and qualified `db.schema` names can never match a real schema as exact entries) — and normalized by the ONE rule — trim, lowercase, drop blank-after-trim entries, deduplicate (first-seen order) — at the **registry's save boundary** (the single place every write path crosses) and again on read, so a row whose allowlist landed by restore or a manual JSONB edit cannot sit silently inert AND what a GET projects always survives an unmodified PUT round-trip; absent/empty = the exclusion floors apply unchanged. See §7A. |
| `properties` | object | optional | Three namespaced maps: `properties.hikari.*` and `properties.jdbc.*` are passthrough (§5); `properties.dialect.*` is TYPED and adapter-validated (§4.2A). On reads, `properties.dialect` carries the non-secret keys only (§3.2's shown/hidden table). |
| `global` | boolean | optional (write) | Workspaces D8: `true` binds the datasource to NO workspace (global, visible to everyone) — **admin-only** to set, either direction. Mutually exclusive with `workspace`. |
| `workspace` | string | optional (write) | Workspaces D8: the workspace to bind to — must be one the caller can access (member or admin); `datasource.validation.workspace_forbidden` otherwise. Default: the caller's ACTIVE workspace. |
| `readonly` | boolean | optional | The §5.7 flag. Editable by whoever may edit the datasource, except on a GLOBAL datasource where only admin may flip it. On update, absent keeps the stored value. |

**Visibility (workspaces design §5.3, normative):** every listing and by-name read sees the ACTIVE
workspace's bound datasources plus all global ones — enforced in the repository's SQL
(`findAllVisible`/`findVisibleByName`), never post-filtered, so paging totals are exact. A
workspace-bound datasource of another workspace behaves as **not-found** on every surface (REST,
MCP, introspection). Datasource NAMES remain a flat GLOBAL namespace (§11.1, workspaces design §3):
`name` is the primary key and the GCM AAD anchor, so a create colliding with another workspace's
datasource name is `datasource.validation.duplicate_name` — by design. Member CUD is gated by
`datapipelines.workspaces.member-datasources-enabled` (configuration §3.17); refusals are
`datasource.validation.workspace_forbidden` (400).

**Normalize-on-read, second instance (050/R3):** the `introspection_include_schemas` rule above
has a sibling — every §5.6 SERVER_MANAGED key is stripped from `properties.hikari` when a row is
read (`DatasourceRow.toDatasource`, the one boundary behind GET, PUT-revalidation and pool build),
so a row written outside the API cannot fail an unmodified GET→PUT round-trip with 400 nor flip
the real pool flag at build time. Normalize at both boundaries; the same rule, twice.

### 3.4 Credential kinds

**A credential travels ONLY in `credential`** — never in `jdbc_url`, never in `properties.*`. `credential.secret` is encrypted at rest (§7.1); `jdbc_url` and `properties_json` are stored plaintext and returned to `read`-scope principals (§3.2). §5.6 refuses credential-bearing keys in both of those carriers, in both directions. There is no third carrier and no exception.

`credential.kind` says WHAT the stored secret is. It decides which fields may be present, and the **adapter** — not the caller — decides where the driver wants it (§4.2 `applyCredential`). A caller says "this is a token"; it never says "put it in the password slot", because that is a fact about a pinned driver.

| `kind` | `username` | `secret` | What it is |
|---|---|---|---|
| `password` | **required** | required on create | A database login. The pre-087 shape, and still the default when a payload names no kind. |
| `token` | optional | required on create | A bearer or personal access token. Placed in the password slot by every shipped adapter (AWS RDS IAM auth, Azure AD auth, and Snowflake's PAT all read it there); a driver that spells it differently overrides `applyCredential` — Databricks wants the literal `UID=token` with the PAT in `PWD`. |
| `private_key` | must be absent | required on create | A PEM private key (Snowflake key-pair auth). Any passphrase is that dialect's own typed field, never a second credential. |
| `service_account_json` | must be absent | required on create | One service-account JSON document (BigQuery). |
| `none` | must be absent | must be absent | Nothing is stored: an IAM role or instance profile supplies the authority, the OS does, or the datasource is an embedded FILE database with no authentication — which is what the demo's SQLite and DuckDB entries always were, previously wearing a dummy password because the contract had no way to say so (§8A.1). |

Values are catalogued in [Enums §5A](enums.md#5a-credentialkind--what-a-datasources-stored-credential-is).

**Which kinds a dialect accepts is a property of its pinned DRIVER**, declared by the adapter (`DialectAdapter.supportedCredentialKinds`) and enforced at save: a kind outside the set is refused with `datasource.validation.properties_invalid` naming what the dialect does accept. Fail-closed, because the alternative is a stored row whose kind the pool build silently ignores.

| Dialect | Accepted kinds | Why |
|---|---|---|
| `POSTGRES`, `MYSQL`, `MSSQL`, `ORACLE` | `password`, `token` | A login, or a bearer token in the password slot (RDS IAM, Azure AD). |
| `H2`, `SQLITE`, `DUCKDB` | `none`, `password` | Embedded engines: H2 may have a login, SQLite and DuckDB have none. `password` stays accepted because every pre-V13 row carries it. |

`private_key` and `service_account_json` are in **no** shipped dialect's set. They are catalogued for the reference targets — Snowflake key-pair auth, BigQuery service accounts — and are refused by every adapter that exists today. Adding the dialect adds the kind to its set; nothing else about the contract, the column, the wire or the encryption changes, which is the point of the seam.

**On update, a kind CHANGE requires a secret.** An update that omits the secret keeps the stored one (§11) — but only while the kind is the same. Changing the kind without supplying a new secret would relabel the stored one as something it is not, and `none` → any other kind would leave `credential_encrypted` NULL against V13's `chk_datasource_credential_present`, turning a refusal the caller can act on into a constraint violation they cannot. Refused with `datasource.validation.password_missing`. Moving TO `none` is the exception and needs nothing: it clears the column.

**Storage is kind-agnostic** (§7.2): whatever the secret is, it is one blob under the same versioned AES-GCM envelope with the datasource name as AAD. Key rotation (§7.3) does not care what the plaintext means, so a new kind needs no crypto work at all.

---

## 4. Supported Dialects

### 4.1 Dialect catalog

The `Dialect` value set is owned by [Type System §5](type-system.md#5-source-to-canonical-mapping-tables); this table is the driver/licensing view of it. Which drivers actually ship in the published image versus opt-in profile versus `lib/` drop-in is stated once in the [Deployment spec](deployment.md) driver matrix.

| Dialect | JDBC driver (Maven coordinates) | License | Notes |
|---|---|---|---|
| `POSTGRES` | `org.postgresql:postgresql` | BSD-2-Clause | Clean license, ships in core. |
| `ORACLE` | `com.oracle.database.jdbc:ojdbc11` | OTN | **User-supplied via optional Gradle profile** — see §10. |
| `MSSQL` | `com.microsoft.sqlserver:mssql-jdbc` | MIT | Clean license, ships in core. |
| `MYSQL` | `com.mysql:mysql-connector-j` | GPL-2.0 with FOSS exception | **Verify redistribution terms before bundling** — treated as user-supplied (`-Pmysql` profile) until verified. See the [Deployment](deployment.md) driver matrix. |
| `H2` | `com.h2database:h2` | MPL 2.0 / EPL 1.0 | Clean license, ships in core (also used for staging). |
| `DUCKDB` | `org.duckdb:duckdb_jdbc` | MIT | Clean license, ships in core. |
| `SQLITE` | `org.xerial:sqlite-jdbc` | Apache 2.0 (with SQLite public-domain bundled) | Clean license, ships in core. |
| `LAKE` | `org.duckdb:duckdb_jdbc` | MIT | Object storage read in place — Parquet and Iceberg on S3 — with DuckDB as the engine. **The same driver as `DUCKDB`, a different §5.6 posture**: the embedded adapter locks `enable_external_access = false` and a lake cannot read S3 with that lock on, so the two are separate dialects rather than one dialect with a mode — a mode would make the refusal set a function of row data, which §5.6's enum-total lookup exists to prevent. Its typed configuration is `properties.dialect.*` and its connect-time setup is §4.2A; the offering built on it is **dp-lake** — the dp-catalog registry, per-table views, registry introspection, engine limits and bundled extensions are §8C. |

### 4.2 Dialect adapter interface

```kotlin
interface DialectAdapter {
    val dialect: Dialect
    val jdbcDriverClassName: String
    val defaultProperties: Map<String, String>          // driver-level defaults; overridable by properties.jdbc.*
    val typeMapper: IngressTypeMapper                   // JDBC types → canonical types
    val refusedPropertyKeys: Set<String>                // dialect additions to the §5.6 refusal set (may add, never shrink)
    val supportedCredentialKinds: Set<CredentialKind>   // §3.4 — what this dialect's pinned driver can authenticate with
    fun applyCredential(config: HikariConfig, datasource: Datasource, secret: String?)  // §3.4 — kind → driver slot
    fun validateJdbcUrl(url: String): ValidationResult  // dialect-specific URL validation (§6.1)
    fun buildHikariConfig(datasource: Datasource): HikariConfig   // entity fields + defaults + properties.* (§5)
}
```

#### Namespace shapes

`namespaceShape` replaces the boolean `schemaArrivesInCatalog`, which could express exactly two shapes — "MySQL" and "everyone else" — and therefore could not describe any of the connectors on the roadmap.

```kotlin
data class NamespaceShape(
    val labels: List<String>,               // the engine's own words, OUTERMOST first; size = depth
    val levels: Int,                        // how many of them a caller can browse and filter on
    val innermostArrivesInCatalog: Boolean, // the former schemaArrivesInCatalog (Connector/J)
)
```

`labels` is not decoration: an agent told to pass `catalog.schema` on Databricks and `project.dataset` on BigQuery writes correct SQL; one told to pass "the schema" guesses. `levels` is often smaller than the depth — Postgres and H2 are `[database, schema]` but a connection can only ever see the database its URL named, so exactly one level is browsable.

| Dialect | `labels` | `levels` | Notes |
|---|---|---|---|
| `POSTGRES`, `H2`, `MSSQL` | `[database, schema]` | 1 | The database is fixed by the JDBC URL. H2's catalog argument IS honoured by the pinned driver (a wrong catalog matches nothing — probed 2026-09-07 against h2 2.3.232); pgjdbc ignores it. Raising MSSQL to 2 needs a probe of mssql-jdbc's cross-database `getTables`, which round 087 did not run. |
| `MYSQL` | `[schema]` | 1 | Arrives in the JDBC **catalog** (Connector/J's default): `innermostArrivesInCatalog = true`. |
| `ORACLE` | `[schema]` | 1 | No catalogs at all — `getCatalogs()` is empty. |
| `SQLITE` | `[]` | 0 | No namespace dimension whatsoever (the former `introspectionSchemaless`, now `NamespaceShape.isFlat`). |
| `DUCKDB` | `[catalog, schema]` | 1 | DuckDB has real catalogs, but this adapter's `enable_external_access = false` lock makes `ATTACH` impossible (verified 2026-09-07 against duckdb_jdbc 1.5.5.1: *"file system operations are disabled by configuration"*), so an embedded datasource has exactly one user catalog. |

**Reference shapes — the contract, not an implementation.** These are the four connectors this seam was designed against; none of them ships today, and each is one `NamespaceShape` away:

| Reference target | `labels` | `levels` | Source (read 2026-09-07) |
|---|---|---|---|
| Snowflake | `[database, schema]` | 2 | `jdbc:snowflake://…/?warehouse=…&db=…&schema=…` — the URL names both. |
| Databricks (Unity Catalog) | `[catalog, schema]` | 2 | docs.databricks.com: assets "follow a three-level namespace (`catalog.schema.object`)"; the JDBC driver's `ConnCatalog`/`ConnSchema` set the connection default for both. |
| BigQuery | `[project, dataset]` | 2 | Google's BigQuery JDBC driver takes `ProjectId` in the URL; datasets are the inner level. |
| A lake over S3 (DuckDB, `ATTACH`ed catalogs) | `[catalog, schema]` | 2 | Verified 2026-09-07 against duckdb_jdbc 1.5.5.1 — see §7A. |

Every one of them is TWO browsable levels, which is what the wire, the filters and the introspector now carry.

`applyCredential` is the ONE place a credential KIND becomes a driver slot. The default covers every shipped dialect: `none` sets neither field (an embedded file database has no login, and Hikari must not be handed a placeholder), and every other supported kind goes into the standard `username`/`password` pair. A dialect whose driver spells a kind differently overrides it, and the caller never learns the difference.

`buildHikariConfig` is the single place the two passthrough maps are applied, so the save-time test pool build (§5.4) and the runtime pool build (§5.2) cannot diverge.

Each dialect has an implementation:
- `PostgresDialectAdapter`
- `OracleDialectAdapter`
- `MssqlDialectAdapter`
- `MysqlDialectAdapter`
- `H2DialectAdapter`
- `DuckdbDialectAdapter`
- `SqliteDialectAdapter`
- `LakeDialectAdapter` — §4.1's `LAKE`: the DuckDB driver without the embedded lock, plus the §4.2A connect-time setup (extensions, the S3 secret, ATTACHes, the §8C.4 limits).

### 4.2A Connect-time setup and typed dialect configuration

Two seams, added in 087 for the same reason: a warehouse or lake connector needs the engine put into a state before any query runs, and that state is CONFIGURATION, not SQL somebody types.

**`connectionInit(datasource): List<String>`** — statements HikariCP runs on every new connection in the pool, via `connectionInitSql`. That property existed and was unreferenced; it is now the seam. The list is joined with `;` into Hikari's single slot, so the sequence stays inside the config the save-time test pool build (§5.4) checks.

Two rules make it safe:

1. **Generated from typed fields, never from operator or author text.** On an embedded engine this SQL runs inside the app's own process; a free-text connect hook would re-open the §5.6 `INIT` / `session_init_sql_file` surface under a friendlier name.
2. **`connectionInitSql` is server-managed** (§5.6, DS-SEC-22). It was reachable through `properties.hikari` before 087 — arbitrary connect-time SQL, in-process on DuckDB and SQLite — and nobody had set it, which is luck rather than containment. It is now refused in both carriers and derived by the adapter.

**`properties.dialect.*`** — a third reserved namespace beside `hikari` and `jdbc` (§12.1's frozen shape, amended additively). Unlike those two it is **typed and adapter-validated**: `DialectAdapter.validateDialectProperties` refuses unknown keys and bad values key by key, and the DEFAULT implementation refuses the whole namespace. A dialect gains `dialect.*` keys by declaring them, never by an adapter forgetting to look — silently ignoring an unrecognized key would let a typo look like a working setting, which is the failure this namespace exists to avoid.

Reference uses, none of them implemented: Snowflake `dialect.warehouse` / `dialect.role`, Databricks `dialect.http_path` / `dialect.catalog`, and the shipped lake adapter's `dialect.catalog.kind` / `catalog.ref` / `region` / `endpoint` / `url_style` / `unsigned` / `attach`.

**The `LAKE` adapter's setup**, in the order it runs:

| Step | Emitted when | What |
|---|---|---|
| `INSTALL`/`LOAD` | `dialect.catalog.kind` is declared | `httpfs` + `aws`, plus `iceberg` for the Iceberg kinds (`glue`, `s3_tables`, `rest`). |
| `CREATE OR REPLACE SECRET dp_lake` | `dialect.catalog.kind` is declared | `TYPE s3` with `PROVIDER credential_chain` for `credential.kind: none` (the IAM chain), or `KEY_ID`/`SECRET` from the stored credential for `kind: password`. Plus `REGION` / `ENDPOINT` / `URL_STYLE` when declared. |
| `ATTACH … (READ_ONLY)` | `dialect.attach` names `alias=location` pairs | Read-only is not configurable: a lake datasource is a READ connector. |

A lake with **no** `catalog.kind` — data on a mounted volume, an NFS export, files a sidecar syncs — emits neither extensions nor a secret. That conditional is not a convenience: `INSTALL httpfs` needs egress to DuckDB's extension repository, and emitting it for a datasource that never touches the network would turn an air-gapped deployment's working configuration into a connect failure.

**What 087 proved, and what it did not.** The local path is proven end to end against a real pool (a LAKE datasource ATTACHing two DuckDB files opens, runs its `connectionInit`, and reports both catalogs — while the same datasource on the `DUCKDB` adapter cannot, because the lock forbids it). The S3 path is asserted at the level of the SQL the adapter generates, not against a bucket: a real S3 needs cloud credentials, and whether `duckdb_jdbc` can `INSTALL`/`LOAD` the three extensions from the app's container — or whether they should be bundled into the image — is spike work the lake connector owns.

### 4.3 Type mapper integration

Each dialect's `typeMapper` implements the per-dialect mapping tables in [Type System §5](type-system.md#5-source-to-canonical-mapping-tables). Adding a new dialect = writing a new `DialectAdapter` + `IngressTypeMapper` (~100–200 lines).

---

## 5. Connection Pool Configuration

HikariCP is the connection pool. Tuning is expressed as **two namespaced passthrough maps** under `properties` — there is no allowlist of supported keys in this spec.

**`properties.hikari.*` — pool properties.** Every entry is applied **verbatim** to `HikariConfig` using HikariCP's own property names and units (camelCase names; all durations in **milliseconds**, as HikariCP defines them). Any property HikariCP supports is therefore usable without a spec change. Illustrative — *not* exhaustive, *not* an allowlist:

**The eight tunable keys (094).** These are what the create/edit dialog renders and what the range rules below police. Every one is HikariCP's own property name; the "Default" column is the EFFECTIVE default this server applies, and the "From" column says which layer supplied it — `dialect` (a `DialectAdapter.defaultHikariProperties` declaration), `server` (this product's), or `HikariCP` (the library's own, read from a fresh `HikariConfig` rather than transcribed). **No shipped dialect overrides a pool setting today**, so the `dialect` layer is empty and the table below is the same for every dialect; the layer exists because the resolution is the adapter's to make and a form that hard-coded today's answer would be silently wrong on the first dialect that disagrees.

| `properties.hikari` key | Unit | Default | From | Floor | Description |
|---|---|---|---|---|---|
| `maximumPoolSize` | count | 10 | HikariCP | ≥ 1 | Max connections to the underlying DB. |
| `minimumIdle` | count | 2 | server | 0 | Idle connections kept warm. `0` = keep nothing warm. Never more than `maximumPoolSize`. |
| `connectionTimeout` | ms | 30000 | HikariCP | ≥ 250 | Max wait to acquire a connection from the pool. `0` = wait forever. |
| `idleTimeout` | ms | 600000 | HikariCP | ≥ 10000 | Idle connection max age. `0` = never close an idle connection. Must sit ≥ 1000 ms below `maxLifetime`. |
| `maxLifetime` | ms | 1800000 | HikariCP | ≥ 30000 | Connection max age — forces reconnect. `0` = no maximum. Keep it under the source DB's own timeout. |
| `keepaliveTime` | ms | 120000 | HikariCP | ≥ 30000 | Idle-connection ping, so a firewall or proxy does not drop it. `0` = off. Must be less than `maxLifetime`. |
| `validationTimeout` | ms | 5000 | HikariCP | ≥ 250 | How long the liveness check may take before a connection is considered dead. |
| `leakDetectionThreshold` | ms | 0 (off) | HikariCP | ≥ 2000 | Log a possible leak when a connection is held longer than this. Must not exceed `maxLifetime`. |
| … any other HikariCP property | — | HikariCP's own | HikariCP | — | Still accepted as passthrough, judged by the save-time pool build (§5.4). Not offered in the dialog. `connectionInitSql` and `readOnly` are §5.6-refused. |

**The ranges are REFUSALS, and that is the point (094).** `HikariConfig.validateNumerics()` enforces almost all of these floors by logging a WARN and **overwriting** the value: `maxLifetime = 5000` silently becomes 1 800 000, `keepaliveTime = 10000` silently becomes 0 (disabled), `leakDetectionThreshold = 500` silently becomes 0, `minimumIdle = 50` against `maximumPoolSize = 10` silently becomes 10, and an `idleTimeout` within a second of `maxLifetime` is silently disabled. (Only `connectionTimeout`, `validationTimeout`, a `maximumPoolSize` below 1 and a negative `minimumIdle` are hard refusals in HikariCP's own setters.) The value the row stores and the screen shows would then not be the value the pool runs with, and nothing would say so — so every one of them is refused at save with `datasource.validation.properties_invalid`, naming the key and the floor. None of these rules is invented: each restates a rewrite HikariCP would otherwise perform, and the test that justifies them builds a real pool with each out-of-range value and asserts the rewrite.

**A field left at its default is not persisted.** The dialog prefills every field with the effective default; only values that DIFFER are written to `properties.hikari`. Freezing today's defaults into every datasource created through the form would mean a later change to a product default reached nothing.

**Reading the effective settings.** `GET /api/v1/datasources/{name}` and the `datasources_get` MCP tool return a `pool` object beside `properties`: each key's effective value, its unit, and its source (`configured` / `dialect_default` / `application_default` / `hikari_default`). `properties.hikari` is what the row STORES — empty for almost every datasource; `pool` is what the pool RUNS with.


**Server-managed keys.** `jdbcUrl`, `username`, `password`, `driverClassName`, `dataSourceClassName`, `poolName`, `metricRegistry`, and `healthCheckRegistry` are derived from the entity and from the dialect adapter. Supplying any of them under `properties.hikari` is a validation failure (`datasource.validation.properties_invalid`) rather than a silent override.

**`properties.jdbc.*` — driver connection properties.** Every entry is passed through as a JDBC connection property (`HikariConfig.addDataSourceProperty`), i.e. what the driver would read from the `Properties` argument of `DriverManager.getConnection`. Values are strings. This is where TLS and driver-specific behavior live; the meaningful keys are the **driver's**, and each dialect adapter contributes `defaultProperties` (§4.2) that callers may override.

Postgres TLS example (`sslmode` values are the [`SslMode`](enums.md#14-sslmode--datasource-tls-mode) catalog):

| `properties.jdbc` key (PG) | Example | Description |
|---|---|---|
| `ssl` | `"true"` | Enable TLS. |
| `sslmode` | `"verify-full"` | `disable` \| `prefer` \| `require` \| `verify-ca` \| `verify-full`. |
| `sslrootcert` | `"/etc/ssl/certs/pg-prod-ca.pem"` | CA cert path on the **server** filesystem. |

Other dialects use their own equivalents (MSSQL `encrypt`/`trustServerCertificate`, MySQL `useSSL`/`sslMode`, Oracle wallet properties); this spec does not restate driver documentation.

**Rationale.** An allowlist of pool keys guarantees the one property an operator needs is the one we forgot. Passthrough plus save-time pool construction (§5.4) gives full coverage *and* fails invalid configuration before the row is written.

### 5.1 Pool sizing guidance

Per datasource, `properties.hikari.maximumPoolSize` should be sized to:
- **Support concurrent pipeline node executions** against this datasource.
- **Stay under the source DB's connection limit.**

Default 10 is conservative. High-traffic datasources can be tuned up. `datapipelines.executor.max-parallel-nodes` ([Configuration §3.2](configuration.md#32-executor), default 4) means up to 4 simultaneous queries against the same datasource within one execution; across executions, the pool is shared, so the practical upper bound is `max-parallel-nodes × concurrent executions touching this datasource`. With N replicas the bound of §5.7 applies — **N pools per datasource: `maximumPoolSize × replicas ≤ the source DB's connection limit`**.

### 5.2 Pool lifecycle

- Pool created lazily on first lease.
- Pool kept alive for the datasource's lifetime.
- On datasource update (PUT), the old pool is **retired** and the next lease builds a new one from the new row.
- On datasource delete (soft), the pool is **retired** and new leases miss it.

#### Retire, then close (094)

Until 094 a save or delete removed the pool from the map and `close()`d it in the same breath — and `HikariDataSource.close()` **aborts** the connections still in use once its shutdown grace elapses. An execution that happened to be mid-statement against that datasource lost its connection and failed. Retirement splits the two halves that were being conflated:

1. **Retire.** The pool leaves the live map at once (new leases miss it and build a fresh pool from the new row, exactly as before), its `minimumIdle` is set to `0` so the house-keeper stops refilling a pool nobody may lease from, and `HikariPoolMXBean.softEvictConnections()` runs: idle connections close now, in-use connections are marked and close when their borrower returns them — **never mid-statement**.
2. **Reap.** A per-instance scheduled tick closes each retired pool once its `activeConnections` reaches 0 — or at the **ceiling** (`datapipelines.datasources.retire-ceiling-seconds`, [Configuration §3.26](configuration.md#326-datasource-pools); default = `node-query-timeout-seconds` + 30 s), whichever comes first. A genuinely hung statement must not pin a deleted datasource's pool forever. A ceiling close logs one WARN naming the datasource and the connections it took down ([Observability §3.4A](observability.md#34a-the-pool-retirement-events-094)) and increments `datapipelines.datasource.pool.hard_closed`; every retirement increments `datapipelines.datasource.pool.retired`.

Retiring pools are held in a **queue, not a map keyed by name**: a datasource saved twice in quick succession, with a lease in between, legitimately has two pools draining at once, and a name-keyed map would drop the first one un-closed. Application shutdown closes everything at once — `ExecutionDrainLifecycle` has already cancelled the live statements by then.

**Reconcile on (re)subscribe.** §5.7's Redis channel is fire-and-forget: a message published while an instance was disconnected is gone, and pub/sub has no replay. So every instance also implements the subscription callback its listener container fires on the initial subscribe AND after every reconnect, and each callback compares the `updated_at` its live pools were built from against the rows — retiring every pool whose row has moved or gone. One two-column query, no Redis key, no TTL, nothing to expire wrong. A missed ping is caught at reconnect, by comparison rather than by replay.

**Concurrency.** `poolFor(datasource)` (§6.1) is called from many executor coroutines at once, so lazy initialization must be **atomic**: pools live in a `ConcurrentHashMap<String, ConnectionPool>` keyed by datasource name and are created with `computeIfAbsent`, so exactly one `HikariDataSource` is constructed per datasource even under a concurrent first-lease burst. Two consequences the implementation must respect:

- The mapping function does no blocking I/O beyond `HikariDataSource` construction (Hikari fills the pool asynchronously; `initializationFailTimeout` is left at Hikari's default for runtime pools, so an unreachable DB surfaces as a lease failure, not a map-wide stall).
- Replacement on update/delete is `remove()`-then-`softEvict()` on the **retired** pool, never anything at all on a pool still reachable from the map — in-flight leases drain against the old instance while new leases go to the new one. The `close()` belongs to the reaper above, not to the save.

### 5.3 Lease lifecycle

```kotlin
suspend fun <T> withConnection(datasourceName: String, block: (Connection) -> T): T {
    val datasource = registry.get(datasourceName) ?: throw DatasourceNotFoundException(...)
    val pool = poolFor(datasource)
    val connection = pool.connection    // blocks up to hikari connectionTimeout
    return try {
        block(connection)               // caller sets Statement.setQueryTimeout per §5.5
    } finally {
        connection.close()              // returns to pool
    }
}
```

No credential decryption happens on this path — the pool already holds the credential from its build (§7.4).

Acquisition timeout (`properties.hikari.connectionTimeout`, 30 000 ms default) exceeded → `pipeline.node.datasource_connection_failed`.

### 5.4 Test pool build (save-time validation)

Datasource create and update run a **test pool build** before the row is written — this is how the passthrough model of §5 stays safe without an allowlist, and it is this entity's instance of the universal validate-on-write principle ([Pipeline Contract §2](pipeline-contract.md#2-design-principles)).

Sequence:

1. The dialect adapter builds a `HikariConfig` from the entity fields plus `defaultProperties`.
2. Every `properties.hikari.*` entry is applied to that `HikariConfig`. HikariCP resolves property names reflectively — an unknown name, a wrong value type, or an out-of-range value throws here.
3. Every `properties.jdbc.*` entry is added via `addDataSourceProperty`.
4. `HikariConfig.validate()` runs, then a `HikariDataSource` is constructed with `initializationFailTimeout = -1` so **no connection to the source database is required** to save the entity.
5. The test pool is closed. Nothing from it is retained.

Any failure in steps 2–4 rejects the save with `datasource.validation.properties_invalid`; the error `details` carry the offending key and the underlying message.

**Limits of this check, stated honestly:** it validates *pool* configuration completely and *driver* property **names** only to the extent the driver rejects unknowns at `Properties` parse time. Driver properties that are only interpreted while opening a socket (a bad `sslrootcert` path, an unsupported `sslmode` value) surface at first connection and via `POST /api/v1/datasources/{name}/test` (§8.1) — not at save. Save-time validation guarantees a *constructible* pool, not a *reachable* database; reachability is deliberately not a save precondition (a datasource may legitimately be registered before its network path exists).

### 5.5 Query timeout precedence

Per-node JDBC statement timeouts resolve in exactly one order:

1. The datasource's `query_timeout_seconds`, **when set** — it wins for every node executing against this datasource.
2. Otherwise `datapipelines.executor.node-query-timeout-seconds` (default 60), defined in [Configuration §3.2](configuration.md#32-executor).

The executor applies the resolved value with `Statement.setQueryTimeout` per node. This is the only place the precedence is stated; other docs reference it. Note this is a *per-statement* timeout and is independent of `datapipelines.executor.execution-timeout-seconds`, which bounds the whole execution.

### 5.6 Refused property keys (normative security exception to passthrough)

Passthrough (§2 principle 8) has one bounded exception: a key is **refused** when the pinned driver treats its value as a class name to instantiate, a file path to read or write, connect-time SQL, or a TLS-verification switch. Refusal applies to **both carriers identically**: a key rejected under `properties.jdbc.*` (`datasource.validation.properties_invalid`) must also be rejected when smuggled into `jdbc_url`'s query/property segment (`datasource.validation.jdbc_url_malformed`) — the URL and the property map are validated against the same union of the server-managed set and the dialect's refusal set.

**Credentials are refused in the URL outright**: `user`, `password`, and driver aliases (e.g. MSSQL `userName`) must arrive via the dedicated `username`/`password` fields — `jdbc_url` is stored plaintext and returned to `read`-scope principals (§3.2), so a credential embedded there defeats §7.1 encryption at rest. This covers a credential smuggled as a query/property key **and** a **userinfo authority in any position** — not only a leading `//user:pw@host` but Oracle's native `jdbc:oracle:thin:user/pw@//host` and H2's `jdbc:h2:tcp://user:pw@host` forms, whose scheme prefix precedes the authority. The authority scan must find the `user[:/]…@` segment wherever it appears, not key off a `//` prefix.

**Secret-valued properties are refused in both carriers**, regardless of whether they also load a class or name a file. `properties.jdbc` is stored plaintext in `properties_json` and returned to `read` scope (§3.2) exactly like `jdbc_url`, so any property whose *value* is credential material is a plaintext-secret exposure. Beyond the enumerated per-dialect keys, a **suffix predicate** over the key name refuses `*password`, `*passwd`, `*pwd`, `*secret`, and `*clientkey` (case-insensitive) — layered on top of the tabled sets so a new driver version's secret key is covered by construction. Named instances that the predicate would otherwise miss (e.g. MSSQL `keyVaultProviderClientKey`) are also listed in the dialect set.

The TLS-verification **switches** refused below (`trustServerCertificate`, `verifyServerCertificate`, `allowPublicKeyRetrieval`) are best-effort: the `sslmode` / `useSSL` / `sslMode` family is deliberately left operator-controlled (§5, [Enums §14](enums.md#14-sslmode--datasource-tls-mode)) because operators legitimately select TLS modes. Refusing a hard "trust anything" switch on one dialect while permitting a mode selector on another is intended, not an inconsistency.

The authoritative enumeration is the module's per-dialect refusal sets, pinned by tests against the driver versions in `libs.versions.toml`; **a driver upgrade must re-review its dialect's set**. Each set must at minimum refuse:

| Dialect | Minimum refused keys (case-insensitive) |
|---|---|
| POSTGRES | `socketFactory`, `socketFactoryArg`, `sslfactory`, `sslfactoryarg`, `sslhostnameverifier`, `authenticationPluginClassName`, `sslkey`, `sslpassword`, `loggerFile`, `loggerLevel` |
| MSSQL | `socketFactoryClass`, `socketFactoryConstructorArg`, `trustStore`, `trustStorePassword`, `trustStoreType`, `keyStoreLocation`, `keyStoreSecret`, `keyStoreAuthentication`, `keyStorePrincipalId`, `clientCertificate`, `clientKey`, `clientKeyPassword`, `trustServerCertificate`, `keyVaultProviderClientKey`, `keyVaultProviderClientId` |
| MYSQL | `allowLoadLocalInfile`, `autoDeserialize`, `allowPublicKeyRetrieval`, plus every `*FactoryClass`/plugin-class property of the pinned Connector/J |
| H2 | `INIT` (RUNSCRIPT vector) |
| SQLITE | `enable_load_extension`, `limit_attached` |
| DUCKDB | the session-init-SQL-file option family of the pinned driver (connect-time fetch-and-run SQL) |
| ORACLE | reviewed set of the pinned ojdbc when built with `-Poracle` (class-loading and file-path properties at minimum) |

**The credential-carrier rule (087), stated once:** a credential travels ONLY in `credential` (§3.4) — never in `jdbc_url`, never in `properties.*`. `credential.secret` is encrypted at rest (§7.1); `jdbc_url` and `properties_json` are stored plaintext and returned to `read`-scope principals (§3.2). There is no third carrier and no exception.

The suffix predicate below covers `…password`, `…passwd`, `…pwd`, `…secret` and `…clientkey`, which is narrow on purpose — a legitimate property cannot end in one of those. Narrowness has a cost the 2026-09-07 contract audit found: it misses every secret whose name ends in a bare `key` or `token`, and both spellings are real in the drivers of the connectors on the roadmap. Verified against the vendors' current documentation, read 2026-09-07: BigQuery's `OAuthPvtKey` is "the service account key … a raw JSON keyfile object or a path", and `OAuthAccessToken`/`OAuthRefreshToken` are the pre-generated-token credentials; Databricks's OAuth token-passthrough flow is `Auth_AccessToken=<token>`. **Those names are ENUMERATED** (`RefusedPropertyKeys.SECRET_VALUED_KEYS`) rather than fixed by widening the suffix list, because `key`/`token` as suffixes would refuse future properties that merely end in those words and destroy the predicate's justification. The rule that follows: **before a dialect ships, every driver property whose value is credential material and whose name escapes the suffixes is added to that set.** The names are cross-dialect, so the guard exists before the dialect does and nobody has to remember.

Under `properties.hikari`, `exceptionOverrideClassName` joins the server-managed refusal set (arbitrary class instantiation). `connectionInitSql` joins it too (DS-SEC-22, 087): HikariCP runs it on every new connection, so it is connect-time SQL — the same §5.6 category as H2's `INIT` and DuckDB's `session_init_sql_file`, and it executes IN THIS PROCESS on the embedded engines. It is additionally server-DERIVED since 087 (§4.2A), so an operator passthrough would also silently replace the adapter's own setup. `readOnly` joins it too (workspaces design §6 layer 2b): the flag is the entity's — the server derives `HikariConfig.isReadOnly` from `is_readonly` — so operator passthrough must not flip it EITHER way on ANY datasource (`datasource.validation.properties_invalid` for `true` on a writable datasource and for `false` on a readonly one alike; silently hardening a writable one would be as much a lie as silently un-hardening a readonly one, and a properties-derived flag is a second source of truth the executor never sees). The refusal sets are part of every dialect adapter's contract — an adapter without a reviewed set is a defect, and the validation path must fail **closed** (an unknown or non-conforming adapter yields no exemption from refusal, never an empty set).

**Embedded in-process dialects harden at the adapter, not just the refusal set (normative).** DuckDB and SQLite run **inside the server JVM**, so author-authored SQL against such a datasource executes in-process — a loaded native extension is arbitrary code in the server, not in a remote database. The refusal set governs `properties.jdbc`/`jdbc_url` keys, but DuckDB **autoloads** known/community extensions with no property involvement at all (`allow_community_extensions` and `autoload_known_extensions` default `true`). Therefore `DuckdbDialectAdapter.defaultProperties` sets, at connect (exact set verified against the pinned driver): `allow_unsigned_extensions=false`, `allow_community_extensions=false`, `autoload_known_extensions=false`, `autoinstall_known_extensions=false`, `enable_external_access=false`. The **load-bearing lock is `enable_external_access=false`**: it is non-overridable by session SQL (a `SET … = true` from author SQL fails — "cannot enable external access while database is running"), and with the filesystem and network off, no `INSTALL`/`LOAD`/`ATTACH`/`read_csv`/`COPY` path is reachable regardless of the autoload toggles. (Verified: `autoload_known_extensions`/`autoinstall_known_extensions` remain settable at runtime, but are **inert** — every actual load path is closed by the external-access lock; a `LOAD json` succeeds only because that extension is statically linked into the pinned jar, not fetched.) These five keys are **additionally refused in `properties.jdbc` / `jdbc_url`** (the DUCKDB entry of the §5.6 refusal set) — because `properties.jdbc` is applied *after* `defaultProperties` (§4.2), an operator could otherwise set `enable_external_access=true` and re-open the RCE surface; for an in-process engine that operator foot-gun is refused, not merely defaulted. `SqliteDialectAdapter.defaultProperties` sets, at connect: `enable_load_extension=false` (explicit hardening; already the driver default) and `limit_attached=0`. The **load-bearing lock is `limit_attached=0`**: this sets `SQLITE_LIMIT_ATTACHED` via the xerial driver's `sqlite3_limit()` call, which runs before author SQL and prevents any `ATTACH DATABASE` — a filesystem-access primitive that would let an attacker open and query any file on the server filesystem. Both keys are **additionally refused in `properties.jdbc` / `jdbc_url`** (the SQLITE entry of the §5.6 refusal set) so an operator cannot set `limit_attached=10` and re-open the surface. This is the datasource analogue of Staging §9.5's de-privileging: an in-process engine must not give author SQL — or an operator's `properties.jdbc` — a code-execution or filesystem-access primitive.

### 5.7 Readonly datasources (flag semantics and enforcement layers)

A datasource flagged `is_readonly` (metadata-db §4.10, V4; workspaces design 2026-08-16 §6, D6) forbids the **three and only three** write-shaped uses in the pipeline contract: a `DML` node's `source`, a `DDL` node's `source`, and any node's `output.target: "datasource"`. `DQL` reads and everything `tempdb` are untouched — the check is on the use, never on the datasource alone. Enforcement is layered, each layer with its own raise site:

1. **Save-time validation** (primary UX): `pipeline.validation.datasource_readonly` (HTTP 400, [Pipeline Contract §12.5](pipeline-contract.md#125-datasource-validations)) — one code, `details` carrying node id + datasource name + which shape fired. **Reads the LIVE row, past the §6.3 metadata cache** (044 F4): the resolver behind the contract registry (`getVisibleLive` / `getLive`) answers from the same row the executor's live backstop answers from, so a row-level flag flip — either direction — is honored by the next save, not by the next cache expiry. A cached read here would have refused VALID saves in the un-flip direction with a wrong 400 that no other layer covered. Cost: one indexed PK read per referenced datasource per save; the REST GET hot path keeps the §6.3 cache.
2. **Executor backstop** (the D10 flip window): `pipeline.node.datasource_readonly` (HTTP 500, [Pipeline Contract §13.4](pipeline-contract.md#134-node-execution)) — the executor re-checks the live registry entry at node execution time, past the §6.3 metadata cache, so a datasource flipped readonly after a pipeline version was saved fails at the NEXT execution, not at the next cache expiry. Covers all three shapes and a composed PIPELINE node's child nodes (each child node passes the same backstop in its own execution). The read is **flag-only** (`isReadonlyLive` — one indexed `SELECT is_readonly`, no credential ciphertext, no properties parse) per write-shaped node execution, and a DQL node whose `output.target: "datasource"` names a readonly datasource is refused at the CONNECT phase, **before** its source query runs (044 F9) — the write-back shell re-checks at write time and remains authoritative.
3. **Pool-level connections** (defense in depth): the dialect adapter builds every pool for a readonly datasource with Hikari `readOnly = true` — the pool-level flag, proven on the real pool build. Treated as defense in depth, not proof, for two stated reasons: JDBC read-only enforcement strength varies by driver (the Postgres driver enforces read-only transactions; others are advisory), and pool→connection propagation of the flag is not guaranteed either (verified against the pinned HikariCP 6.3.0 + H2 2.3.232: the flag reaches the pool, not the leased connection). `properties.hikari.readOnly` is §5.6-refused in both directions for exactly this reason (§5.6, above). **DuckDB-family exception (089, corrected 2026-09-08):** the DuckDB driver refuses to CHANGE a connection's read-only state, and HikariCP calls `setReadOnly` exactly when the connection's own `isReadOnly()` differs from the pool flag — so for `DUCKDB` and `LAKE` the pool flag MIRRORS the state the connection opens in (`properties.jdbc.access_mode: READ_ONLY` ⇒ `true`, otherwise `false`) instead of following `readonly`; the D6 layer-2a executor backstop is the enforcement that remains. Forcing it either way fails the whole pool build: forced on broke the `:memory:` lake (089 §F), forced off broke every `--demo trade` datasource (093 §2). **Cross-instance eviction (050/R1, M3 closed for registry-mediated writes):** every registry save/delete publishes the datasource name on the Redis channel `dp:datasource-invalidated` after the row commits, beside the synchronous local eviction; every instance subscribes (Spring's `RedisMessageListenerContainer`, subscribed before the instance serves traffic) and evicts its pool for that name, so the **next use rebuilds from the row** — an operator repointing a datasource or rotating a password through instance A no longer leaves instance B on stale credentials/URL until restart. The publishing instance does not act on its own message (local eviction already ran); a Redis fault at publish degrades to a WARN, never a failed save. Global datasources (`workspace = null`) ride the same channel — instances stay symmetric, deliberately (R1 rejected workspace→instance affinity). **Sizing under replication:** with N instances a datasource has N pools, so `properties.hikari.maximumPoolSize × replicas ≤ the customer database's connection limit` must hold for every datasource. **Known residual window (044 F5, narrowed):** a **row-level** flip written out of band (manual SQL/restore, the D10 channel — not a registry save) still publishes nothing and leaves pre-flip pools serving until the next registry save/delete on that datasource or a restart; the window is bounded in practice: every write-shaped path crosses layer 2's live check first, so a stale **writable** pool cannot ship a write layer 2 refuses — the stale pool only serves reads the flag never forbade. This is the same mechanism the architecture audit tracked as M3, resolved 050 ([ARCH-AUDIT-2026-08](ARCH-AUDIT-2026-08.md#m3--datasource-connection-pools-never-expire-cross-instance--critical-verified)).

**The backstop's null semantics (044, normative).** The live read is three-valued, and all three values are decisions — "I could not read the row" is **never** "there is no restriction":

- live row readonly → refuse (`pipeline.node.datasource_readonly`);
- live row writable → proceed;
- **no live row** (soft-deleted by manual SQL — the D10 channel — or unknown) → refuse (`pipeline.node.datasource_not_found`): a cached entry and a warm pool must not carry a write to a datasource the live registry no longer holds;
- **the read itself failed** (metadata database unavailable) → refuse with `pipeline.execution.aborted`, the message naming the **metadata** database, never the healthy target datasource.

**Metadata-DB dependency (044 F3, stated):** every write-shaped node execution — and, since F4, every save-time validation — performs the live read above, so both depend on metadata-DB availability at that moment. This is the cost of fail-closed: an unreachable metadata DB refuses writes rather than guessing the flag, and the refusal says which database to page.

**Normative deployment guidance (layer 3): the flag is contract, not containment.** A datasource whose data must not change gets a **SELECT-only database user** regardless of the flag — the flag gives agents fast machine-readable feedback and the executor a deterministic refusal, but only the credential gives the guarantee (the datapipelines.co demo's seeded datasources are `global` + `readonly` AND run under SELECT-only users).

The flag is a first-class writable field since the workspaces surfaces slice. Whoever may edit a datasource may flip `readonly` on it — EXCEPT on a GLOBAL datasource, where only a global admin may (workspaces design §6 last paragraph, D8). Every flag write crosses the registry's save boundary, so the pool is drained on update and rebuilds under the new `readOnly` setting at the next lease; there is no column-level UPDATE path, by construction. The §5.2 pool lifecycle is the mechanism — this paragraph is the pointer, not a second statement of it.

---

## 6. Datasource Registry

### 6.1 Interface

```kotlin
interface DatasourceRegistry {
    fun list(dialect: Dialect? = null): List<Datasource>   // optional filter backs GET /datasources?dialect= (§11)
    fun get(name: String): Datasource?       // null if not registered or soft-deleted
    fun getLive(name: String): Datasource?               // full live row, past the §6.3 cache (044 F4/F6 — abstract)
    fun getVisibleLive(name: String, workspaceId: UUID): Datasource?   // save-time validation's live read (§5.7 layer 1, 044 F4)
    fun isReadonlyLive(name: String): Boolean?           // the executor backstop's flag-only read (§5.7 layer 2, 044 F2/F7 — abstract)
    fun exists(name: String): Boolean
    fun save(datasource: Datasource, actor: UUID): Datasource   // create or update; validates first (§5.4, §9). actor: created_by (Metadata DB §4.10) + the §7.4 audit actor
    fun delete(name: String): DeleteResult         // soft delete; fails if in use
    fun poolFor(datasource: Datasource): ConnectionPool   // lazy, thread-safe (§5.2)
    fun testConnection(name: String): TestResult?  // null = no such datasource (caller maps to 404); §8.1's "HTTP 200 always" applies only when it exists — records the outcome on the row (§8.1B)
    fun validate(datasource: Datasource): ValidationResult
    fun resyncBootstrapCredential(name: String, fileCredential: String): CredentialResync  // §8A.3 rule 3; defaults to NOT_APPLICABLE
}
```

(v1.4: `save` gained the required `actor` parameter — `created_by` is `NOT NULL` and §7.4 audit events record an actor, so the v1.1 signature was unimplementable; `list` gained the optional dialect filter §11 already promises; `testConnection` returns `null` for an unknown name instead of a synthetic failed `TestResult` whose `errorClass` was not an FQCN. 044: the live reads are first-class — `getLive` and `isReadonlyLive` are **abstract** (020 F6: a cached default was the exact hole the live read exists to close), `getVisibleLive` defaults to `getVisible` for in-memory fakes; visibility-scoped `listVisible`/`getVisible` (workspaces design §5.3) are elided from this sketch, not from the interface.)

Return types:

```kotlin
/** Outcome of a soft delete. Never throws for the in-use case — the caller needs the list. */
data class DeleteResult(
    val deleted: Boolean,
    val name: String,
    val errorCode: String? = null,          // 'datasource.in_use' when deleted = false
    val references: List<DatasourceReference> = emptyList()  // the ANY-VERSION scan (§6.2): one entry per referencing NODE
) {
    val referencingPipelines: List<String> get() = references.map { it.pipelineName }.distinct()
}

/** One node of one stored pipeline version referencing a datasource — the delete guard's evidence (§6.2). */
data class DatasourceReference(
    val pipelineName: String,
    val pipelineVersion: Int,               // WHICH stored version carries it — the field the old guard did not have
    val versionStatus: String,              // DRAFT | RELEASED | DISCARDED
    val nodeId: String
)

/** Which branch of §8A.3 rule 3 ran for one bootstrap entry. */
enum class CredentialResync {
    NO_LIVE_ROW, CREDENTIAL_MATCHES, STORED_WORKS, RESYNCED, BOTH_FAILED, STORED_UNREADABLE, NOT_APPLICABLE
}

/** The stored outcome of the last connection test (§8.1B) — the persisted twin of TestResult. */
data class DatasourceTestOutcome(
    val testedAt: Instant,
    val ok: Boolean,
    val message: String? = null             // driver message on failure, server version on success; scrubbed at the probe
)

/** Outcome of a live connectivity probe (§8.1). Failure is data, not an exception. */
data class TestResult(
    val connected: Boolean,
    val testedAt: Instant,
    val latencyMs: Long? = null,            // present when connected
    val serverVersion: String? = null,      // DatabaseMetaData.getDatabaseProductVersion(), when connected
    val error: String? = null,              // message, when not connected
    val errorClass: String? = null          // exception FQCN, when not connected
)

/** Outcome of save-time validation (§5.4, §9). Also returned by DialectAdapter.validateJdbcUrl. */
data class ValidationResult(
    val valid: Boolean,
    val errors: List<ValidationError> = emptyList()
) {
    data class ValidationError(
        val code: String,                   // a §9 code, e.g. 'datasource.validation.properties_invalid'
        val field: String?,                 // JSON pointer-ish path, e.g. 'properties.hikari.maximumPoolSize'
        val message: String                 // human-readable; safe to surface (never contains credentials)
    )
    companion object { fun ok() = ValidationResult(true) }
}
```

`ValidationResult.errors` is **complete, not first-failure** — a save returns every rule that failed so the UI can render one form pass. `TestResult.error` and `ValidationResult.ValidationError.message` are redaction-scrubbed: neither ever carries `password` or the credential portion of a JDBC URL (redaction rules: [Observability spec](observability.md)).

### 6.2 In-use check on delete

`DELETE /api/v1/datasources/{name}` fails with `datasource.in_use` if any non-deleted pipeline
references this name **in any version it has ever stored** — DRAFT, RELEASED and DISCARDED
alike. Pipeline versions are immutable and executable by explicit version
([Versioning §7.1](versioning.md#71-authoring-reads-return-the-working-version-039)), so a reference living only in a released
v1 that a later v2 dropped is a live reference: deleting the datasource out from under it fails
that version's next execution at connect.

This is the **any-version** scan, and it is deliberately not the one the pipelines listing uses.
The two questions are different and 040 already split them for templates:

| Question | Scan | Reads |
|---|---|---|
| "Which pipelines am I looking at?" (the listing's datasource filter) | working version | each pipeline's current version |
| "Would deleting this break anything?" (this guard) | **any version ever** | every stored version of every live pipeline |

Conflating them is how the guard under-reports. It did: the delete guard read `current_version`
only until 2026-09-03, so a released pipeline whose older version pinned the datasource was
invisible and the delete succeeded.

The error response carries the distinct pipeline names AND one entry per referencing node with
the pipeline version and that version's status — the shape `template.in_use` uses — because the
version is what tells the operator which body to go and change.

**The screen asks the same question, first (094).** The datasources list's **Delete** action opens
a dialog that runs THIS scan before it offers anything, and renders its rows as
`pipeline › node (v3 released)`. On the in-use branch there is no confirm button at all, so the
refusal cannot be clicked past; only an unused datasource gets a confirm, and that confirm names
it. The dialog and the `409` cannot disagree because they are the same scan — but the POST
**re-runs** the guard anyway: a pipeline can start referencing the datasource between the dialog
opening and the button being pressed, and the screen is never the authority. The `readonly` /
`global` D8 rules apply exactly as they do for update (a member deleting a global datasource is
told so before the scan even runs).

Deleting a datasource **retires** its pool on every instance rather than closing it (§5.2), so a
query already running against it finishes on the connection it holds. That is what the dialog's
"queries already running finish first" sentence promises, and what the two-instance harness
measures.

### 6.3 Cache

- Datasource metadata cached in memory (low churn).
- Cache invalidated on create/update/delete — **local instance only**.
- Entries carry a **short TTL** (default 60s, matching the auth liveness cache) so a change made on one instance becomes visible on every other instance within the TTL. The local invalidation is an immediacy optimization for the instance that made the change; the TTL is what bounds cross-instance staleness in the multi-instance deployment model (auth §8) — without it, an operator repointing a datasource would be invisible to sibling instances until restart.
- **Negative lookups are never cached** (a miss re-reads), so the cache cannot be grown by `GET`s for non-existent names and is bounded by the number of real datasources.
- Connection pools cached separately (lazy init, see §5.2); the pool-build path bypasses this cache (it must reload the encrypted credential).

---

## 7. Credential Storage

### 7.1 Encryption at rest

Passwords are **never** stored in plaintext. Encryption approach:

- **AES-256-GCM**. Stored value = `version ‖ nonce ‖ ciphertext ‖ auth tag`; a fresh random 96-bit nonce per encryption.
- **The first byte is the KEY VERSION** the value was sealed under (`1..255`; `0` is reserved as "not a version", so a zeroed page cannot forge one). It selects which data key decrypts the row. It is not itself authenticated and does not need to be: it picks a key, and a wrong key fails the GCM tag — so flipping it is a tamper signal either way. A blob whose version this deployment holds no key for is **refused** with the same decryption error, which is the "wrong deployment / retired key" signal.
- **The datasource `name` is bound as GCM associated data (AAD)** on both encrypt and decrypt. A stored ciphertext therefore decrypts only under the name it was sealed with: a row copied or renamed at the database level fails the tag rather than silently decrypting, closing the lift-a-ciphertext-to-another-row attack (`name` is immutable, §11.1, so this never obstructs legitimate use). Any code path that decrypts — pool build, connection test, and the §7.3 rotation pass — MUST pass the row's name as AAD.
- Data keys come from a **key provider** (§7.1.1), selected by `datapipelines.db.key-provider` ([Configuration §3.20](configuration.md#320-credential-key-provider)) and defaulting to `env`.
- Keys are **required and fail-fast**: a missing key, invalid base64, a key that is not exactly 32 bytes, or a provider whose backing service is unreachable **stops startup**. There is no fallback chain — no implicit KMS lookup, no generated key file.

**Why no fallback.** A silently generated key file is how credentials become undecryptable on the next redeploy (new container, new file, every stored password now garbage) — the failure appears long after the deploy that caused it, and no backup of the metadata DB can repair it. Refusing to start is the cheap failure.

#### 7.1.1 The key provider seam

The seam is the **key**, not the ciphertext. One `CredentialEncryptor` implements the crypto above; where its keys come from is an interface:

```kotlin
package co.datapipelines.datasources.crypto

/** A 32-byte AES key and the version the ciphertext will carry. */
class DataKey(val version: Int, val bytes: ByteArray)   // version 1..255

interface KeyProvider {
    val name: String                     // the config value that selects it: "env", "aws-kms", …
    fun current(): DataKey
    fun byVersion(version: Int): DataKey?
}
```

`current()` supplies the key for every new encryption; `byVersion()` the key for a row written under an earlier one. An unknown version returns `null`, which the encryptor turns into the decryption error. Three invariants every implementation owes, pinned by one shared suite (`KeyProviderContractTest`):

1. `current()` is **stable for the life of the process** — the encryptor caches it at construction.
2. `byVersion(v)` keeps answering for any `v` that `current()` ever returned on this deployment, for as long as a stored row carries it.
3. Both fail at **boot**, not at first decrypt, when the backing service is unreachable — a misconfigured pod must never start and serve.

A provider never sees a password, and never logs key material.

**The shipped provider is `env`:** `datapipelines.db.encryption-key` is version 1 forever; optional `datapipelines.db.encryption-keys` adds `version → base64` entries for a rotation; `datapipelines.db.encryption-key-current` selects which one new writes use (default: the highest configured). All of it is [Configuration §3.20](configuration.md#320-credential-key-provider).

**Implementing another one** — AWS KMS, GCP KMS, Azure Key Vault, Vault transit — is a written procedure, not a design exercise: see [Key providers](key-providers.md). It is an implementation of the contract above plus a config block, a validator branch and a subclass of the shared contract suite. Nothing in the crypto changes.

### 7.2 Schema

**DDL authority: [Metadata DB §4.10](metadata-db.md#410-datasources).** No DDL block lives in this spec — `metadata-db.md` is the only doc that writes DDL, so there is exactly one table definition to keep true.

The semantics this spec depends on, which the DDL must satisfy:

- `name` is the **primary key** (`TEXT`), constrained to 63 characters and to the identifier regex of §9 via `CHECK` — pipelines reference datasources by this value, so it is also the immutability anchor (§11.1).
- `description` is **optional** (nullable / no `NOT NULL` requirement) — matching §3.3.
- `credential_encrypted` (V13; `password_encrypted` before it) is `BYTEA` — AES-256-GCM output per §7.1 (`version ‖ nonce ‖ ciphertext ‖ tag`), never plaintext, never returned by any endpoint. Rows written before the version byte existed were prefixed with `0x01` by a one-off migration; the application accepts ONLY versioned blobs and never guesses the old layout. **NULLABLE since V13**, and null exactly when `credential_kind = 'none'` — the blob itself is kind-agnostic (§3.4), so a token, a PEM and a service-account document are sealed exactly as a password is.
- `credential_kind` is `TEXT NOT NULL DEFAULT 'password'` with a `CHECK` over the [Enums §5A](enums.md#5a-credentialkind--what-a-datasources-stored-credential-is) set (V13, §3.4); `username` is nullable, and two further CHECKs pin the §3.4 field rules at the column.
- `properties_json` is `JSONB` and holds the §5 object verbatim (`{"hikari": {...}, "jdbc": {...}}`), defaulting to `{}`.
- `dialect` is `TEXT` with a `CHECK` constraint enumerating the [Type System §5](type-system.md#5-source-to-canonical-mapping-tables) dialect values — a database-level guard duplicating the §9 application check on purpose.
- `created_at` / `updated_at` are `TIMESTAMPTZ` (UTC); `updated_at` is set by the application in every UPDATE.
- `created_by` is a `UUID` **foreign key to `users(id)`**.
- `is_deleted` supports soft delete (§6.2); lookups filter it.

### 7.3 Key rotation

The version byte (§7.1) makes rotation **lazy-safe**: there is no big-bang re-encrypt and no window in which two keys must be applied by hand.

**The flow an operator runs:**

1. Generate a key: `openssl rand -base64 32`.
2. Add it as the next version and make it current ([Configuration §3.20](configuration.md#320-credential-key-provider)):

   ```yaml
   datapipelines:
     db:
       encryption-key: ${DATAPIPELINES_DB_ENCRYPTION_KEY}          # version 1, unchanged
       encryption-keys:
         2: ${DATAPIPELINES_DB_ENCRYPTION_KEY_V2}
       encryption-key-current: 2
   ```

3. Restart. Every NEW encryption carries version 2. Every existing row keeps decrypting under the version it carries, because version 1 is still configured.
4. Rows migrate to the current key **as their passwords are next saved** — no pass over the table, no downtime.
5. Retire version 1 only when no row still carries it. That is one query, and it is the only thing that answers the question:

   ```sql
   SELECT get_byte(credential_encrypted, 0) AS key_version, count(*)
   FROM datasources
   WHERE credential_encrypted IS NOT NULL
   GROUP BY 1 ORDER BY 1;
   ```

   While that reports any `key_version = 1`, removing the version-1 key makes those rows undecryptable. When it reports only `2`, `datapipelines.db.encryption-key` may be replaced with the version-2 material (and `encryption-keys` emptied) at the next restart.

**Deliberately NOT shipped:** a rotation *endpoint* or CLI that decrypts and re-encrypts every row. It is not needed for correctness under versioned blobs, and it is a bulk-decrypt surface — every stored password in memory in one pass, behind a trigger an operator can be talked into pulling. The audit event `datasource.key_rotation` ([Enums §15](enums.md#15-authauditevent--auth-audit-log-events)) stays registered and is emitted by nothing. An operator who needs every row moved NOW — a suspected key compromise — re-enters the affected credentials, which also rotates the *database* passwords, which is what a key compromise actually calls for.

A re-encrypt, whenever one happens, must carry the row's `name` through both halves as AAD (§7.1): a decrypt/re-encrypt that drops it fails every GCM tag.

### 7.4 Decryption points and audit log

**Decryption happens once per pool build — not once per connection lease.** HikariCP necessarily holds the credential for the pool's lifetime (it opens new physical connections on its own schedule, without the caller present), so a per-lease decrypt would be theatre: the plaintext is already resident in the pool. The credential is decrypted exactly at:

1. **Pool build / rebuild** — lazy first lease (§5.2), or a rebuild after datasource update or key rotation.
2. **Connection test** (§8.1) when it constructs a throwaway pool for a datasource with no live pool.
3. **Key rotation** (§7.3), which decrypts every row in one pass.

An audit event is written at each of those points — **never per lease**. The module emits them through an injected audit sink (a `fun interface` sibling of `DatasourceReferences`, with a no-op default so the module stays dependent on `typesystem` alone); the application wires the sink onto the shared `audit_log` writer at assembly (v1.4). The earlier "audit every lease" model produced unbounded event volume (one row per query, per node, per execution) that recorded nothing the execution record did not already contain, and it implied a decrypt that does not happen.

**`pool_build` vs `pool_rebuild` timing.** A datasource update evicts the live pool immediately (synchronously, with the operator's identity in hand) but decrypts nothing then — the lazy rebuild on the next lease is what decrypts, and it emits its own `pool_build` (actor = system, executor-initiated). So an update to a datasource with a live pool produces `pool_rebuild` (operator actor, marks the eviction) followed later by `pool_build` (system actor, marks the actual decryption). `pool_rebuild` exists to capture the operator who triggered the change — an identity that is gone by the time the lazy build runs — not to mark a decryption of its own. `actor` is the system principal for executor-initiated pool builds and the operator's user id for operator-initiated actions (update, connection test); `cause` (execution id + node id) is populated only for a `pool_build` triggered from an execution, where the executor supplies the context.

Each event records:
- Timestamp (`TIMESTAMPTZ`, UTC)
- Datasource name
- Event name: `datasource.pool_build` | `datasource.pool_rebuild` | `datasource.connection_test` | `datasource.key_rotation` (registered in [Enums §15](enums.md#15-authauditevent--auth-audit-log-events) alongside the auth audit events)
- Actor (user id for operator-initiated actions, or the system principal for executor-initiated pool builds)
- Cause, when the trigger is `pool_build` from an execution: the execution id and node id that took the first lease

Stored in the audit log table ([Metadata DB §4.3](metadata-db.md#43-audit_log)); retained per `datapipelines.audit.retention-days` ([Configuration §3.12](configuration.md#312-audit)).

Per-node datasource *usage* remains observable without any credential-audit event: it is already recorded on the execution and its per-node stats.

---

## 7A. Schema Introspection

Shipped in v1.1 (was datasources §14 future work). Read-only live schema metadata over a registered datasource's JDBC `DatabaseMetaData`, so agents can enumerate real schemas, tables and columns instead of hallucinating them when authoring SQL templates.

Three read operations, all served by the module's `SchemaIntrospector` through the existing `DatasourceRegistry` pool (`poolFor`, §5.2 — introspection opens a live connection, exactly like a connection test). They form the **only introspection flow**: schemas → tables → columns — list the schemas, list one schema's tables, then read columns for only the tables the SQL needs. Nothing bundles columns into a table listing; table listings stay lightweight so more tables fit in one response.

| Operation | Returns | Notes |
|---|---|---|
| Schemas | `{"schemas": ["label", ...], "entries": [{namespace: [...], label}], "truncated": bool}` | The flow's entry point: the driver-reported namespaces, engine system schemas excluded. `entries` (087) is the shape to read — `namespace` is the ordered path a caller passes back as a filter, `label` its last segment; `schemas` repeats the labels for pre-087 clients and is kept for one release. Two entries can share a label and differ only by their outer segment, which is exactly what the array form exists for. On MySQL the databases arrive as JDBC catalogs (Connector/J defaults), so the listing reads `getCatalogs()`/TABLE_CAT — the `innermostArrivesInCatalog` routing the other operations use; `getSchemas()` there reports a single blank schema. For every other dialect it reads `getSchemas()`, whose TABLE_CATALOG column is what makes two same-named schemas in different catalogs two DIFFERENT entries. **An empty list is a valid result**, not an error: a schemaless dialect (SQLite, single-db DuckDB) has no schemas to list. `getSchemas()` carries no remarks, so none are returned. Capped at **2000 schemas** (`truncated: true` when the cap dropped any) — the listing walks `getCatalogs()`/`getSchemas()` under the pooled lease, and on MySQL catalog routing that is every database the server grants, so the walk and the payload are bounded like the tables listing. |
| Tables | `{"tables": [{namespace: [...], schema, name, type, remarks?}], "truncated": bool}` | Tables and views; `namespace` (087) is the containing path outermost-first and `schema` is its last segment, kept for one release so a pre-087 client reads what it always read; `type` is the driver's raw JDBC table type (`TABLE`, `VIEW`, `BASE TABLE`, ...); `remarks` is the engine-stored comment (JDBC REMARKS), omitted when the driver/database has none. Optional namespace filter (see below); without one the listing **spans namespaces** — pass each table's reported `namespace` to the columns operation. A listing cannot merge (every row carries its own schema), so there is deliberately **no unknown-current-schema guard here** — the guard, and its cannot-merge rationale, belong to the columns operation alone. Capped at **2000 tables**; `truncated: true` when the cap dropped some. Nothing bundles columns into this listing — it stays lightweight so more tables fit in one response; columns are read per table. |
| Columns (one table) | `[{name, type, precision?, scale?, nullable?, source_type, warnings, remarks?}]` | `type` is the canonical Type System type, mapped through the dialect's ingress type mapper ([Type System §5](type-system.md#5-source-to-canonical-mapping-tables)); `source_type` is the driver's own type name; `warnings` carries the mapper's warning messages (§8.2/§10.5), empty when the mapping was clean; `remarks` is the engine-stored column comment (JDBC REMARKS), omitted when there is none. Pass the table name exactly as the tables operation returned it — JDBC metadata name matching is case-sensitive. System-schema rows are excluded. Without a namespace filter the read defaults to the connection's **current namespace** — its current catalog AND schema, routed per dialect like an explicit filter — so same-named tables in different schemas, or in different catalogs, cannot merge their columns; a datasource that reports **no current schema** (or the JDBC blank sentinel, which means "objects without a catalog/schema", not a schema named `""`) cannot honor that default, and the unfiltered read it would fall back to is exactly the merge the contract forbids — such a read **fails** with the catalogued `pipeline.execution.parameter_required` (the caller lists schemas and passes one explicitly; the schemas operation keeps its unfiltered-minus-system listing, which is how the caller recovers). The flat dialects (SQLite: no namespace dimension at all, so same-named tables cannot exist in different schemas) are the deliberate exception and keep the unqualified read. |

The table-type vocabulary and the system-schema exclusion are **per-dialect properties on the `DialectAdapter`** (next to the type mapper): every dialect that has an `information_schema` excludes it (case-insensitive); Postgres additionally lists `PARTITIONED TABLE`, `MATERIALIZED VIEW` and `FOREIGN TABLE`, and excludes `pg_catalog` as well. System catalogs that report under the dedicated JDBC types (`SYSTEM TABLE`, `SYSTEM VIEW`) are kept out by the type vocabulary itself — but that mechanism has holes: some engines report their system schemas under the plain types (MySQL's Connector/J reports `sys`, `performance_schema` and `mysql` as ordinary TABLE/VIEW rows), which is why the per-dialect schema lists carry more: MySQL excludes `mysql`, `performance_schema`, `sys`; Oracle excludes `SYS`, `SYSTEM`, `OUTLN`, `XDB`, `CTXSYS`, `MDSYS`, `ORDSYS`, `DBSNMP`, `WMSYS`, `AUDSYS`, `OLAPSYS`, `XS$NULL` and `APEX_*` (a prefix entry — Oracle versions its APEX schemas, e.g. `APEX_240100`); SQL Server excludes `sys` alongside `INFORMATION_SCHEMA`, plus the built-in fixed-role/special schemas every SQL Server database carries (`db_owner`, `db_accessadmin`, `db_securityadmin`, `db_ddladmin`, `db_backupoperator`, `db_datareader`, `db_datawriter`, `db_denydatareader`, `db_denydatawriter`, `guest` — `dbo` is deliberately NOT excluded: it is the database's default user schema); DuckDB, being Postgres-lineage, excludes `pg_catalog` beside `information_schema`. The MSSQL and DuckDB lists are floors, deliberately known-incomplete exactly like Oracle's — no arm64 containers exist for either dialect (pre-existing), so both are unit-verified against mocked metadata rather than container-verified. **These lists are a floor, explicitly known-incomplete** — they name the schemas the pinned drivers verifiably report as plain user rows, not every schema an engine ships; extending them is additive.

**The floors can over-exclude, and `introspection_include_schemas` is the escape hatch.** A prefix entry cannot tell the engine's schemas from a customer's own: any Oracle schema starting `APEX_` — including a team's own `APEX_REPORTING` reporting schema — is invisible to all three operations, with no warning, and the authoring agent is then told the data does not exist. A datasource registered with `introspection_include_schemas: ["apex_reporting"]` (§3.3) exempts exactly that name from the exclusion in all three operations — every other floor entry, including the rest of the `apex_*` family, stays hidden. Exact names — or dotted NAMESPACES (087: `a1.sales` exempts one catalog's `sales` while leaving the other's excluded, which a bare name cannot express) — over the legal-identifier alphabet of the supported dialects: letters, digits, `_`, `$`, `#`, lowercase, in one or more dot-separated non-empty segments (the prefix language belongs to the floors; a pattern or quoted identifier would look like it exempts a family while exempting nothing, so anything outside the alphabet is rejected at save, while `_` is an ordinary name character exempting the exactly-named schema). A single segment matches the schema level on every dialect, so every entry stored before 087 keeps working; normalized by the ONE rule — trim, lowercase, drop blank-after-trim entries, deduplicate (first-seen order) — at the registry's save boundary (every write path crosses it) and again at the repository's read boundary (restore and manual JSONB edits bypass save, and an unnormalized entry silently exempts nothing); matching case-insensitive like the exclusion itself; absent/empty = today's behavior.

**Namespace filters (087).** All three operations speak the dialect's `NamespaceShape` (§4.2), not "a schema".

- REST takes `?namespace=a1&namespace=sales` (repeated) or `?namespace=a1.sales` (dotted shorthand); MCP takes `"namespace": ["a1", "sales"]`. The pre-087 `schema` parameter keeps working forever (§12.1) and accepts the dotted form too, so an agent can pass back exactly what a listing gave it. `namespace` wins when both are present.
- Routing: a catalog-routing dialect takes its single level in the JDBC **catalog** argument and leaves the pattern null (Connector/J's default — otherwise a filter selects nothing and every table reports a null schema). Every other dialect takes the innermost segment in `schemaPattern` and the **next-outer segment in the catalog argument**. That last part is what 087 fixed: the catalog argument was a hard-coded `null` on all three operations, which is correct only while a connection has exactly one catalog.
- A filter DEEPER than the dialect's namespace matches nothing and returns an empty result — it cannot name a real place, and silently dropping its extra segments would answer a different question.

**The merge this fixed was measured, not inferred.** Verified 2026-09-07 against duckdb_jdbc 1.5.5.1 with two `ATTACH`ed database files, each holding a `sales.orders`: the schemas listing reported two indistinguishable `sales` rows, and `getColumns(null, "sales", "orders", "%")` returned **both tables' columns as one table's** (`id, one_col, id, two_col`). The same read qualified by catalog returns each table's own columns. An authoring agent handed the merged list writes SQL against columns that do not exist in the table it named.

Rules:

- **Scope: `author`** on every surface (REST and MCP), matching the [§8.1](#81-post-apiv1datasourcesnametest) connection-test precedent — introspection opens a live connection against a production datasource, and its stated consumer (authoring agents) holds `author` ([Auth §7.6](auth.md#76-scope--operation-matrix-authoritative)).
- **Read-only by construction**: these three operations issue only `DatabaseMetaData` calls, no statements. The statement-executing siblings landed later and are non-writing by their own construction: §7C's table statistics run bounded catalog queries, and §7D's probe runs one classified SELECT — neither writes, and neither relaxes anything said here.
- **`table`, `schema` and `namespace` filters are exact-match identifiers, not LIKE patterns** — `_` and `%` in a name are escaped with the driver's `getSearchStringEscape()`, so a filter for `order_items` cannot match a sibling table like `order1items`. The escape applies only to the true pattern arguments (`schemaPattern`, `tableNamePattern`); the JDBC **catalog argument is a literal** ("must match the catalog name as it is stored") and is never escaped — an escaped catalog would match nothing for a MySQL database whose stored name carries `_`/`%`.
- **An unknown table, schema or namespace filter is not an error** — it matches nothing and returns an empty list (the house filter philosophy; see `datasources_list`'s dialect filter in [MCP §6.2.10](mcp-server.md#6210-datasources_list)).
- **An unknown datasource name is `datasource.not_found`** ([Pipeline Contract §13.8](pipeline-contract.md#138-datasource)).
- **A connection failure during introspection is `pipeline.execution.datasource_unreachable`** ([Pipeline Contract §13.8](pipeline-contract.md#138-datasource); HTTP 502 on REST, an `isError` envelope on MCP) — a customer database being down is not a server error: no raw 500, no `-32603`, logged at WARN without a stack. The translation happens at the introspector's lease boundary and covers **both** failure families: the `SQLException` of a refused/timed-out lease or a connection that died mid-read, and the RuntimeException family of pool construction (`PoolInitializationException` at first lease on a down database, a missing driver class, a property rejected at parse time). Post-lease the SQLException translation narrows to the **connection family only** — SQLState class 08 (checked on the exception itself and along its `cause`/`nextException` chains, because some drivers carry the state only on a wrapped exception), the JDBC connection-exception subclasses, `SQLRecoverableException`, `SQLTimeoutException`, and the per-driver connection-loss knowledge: SQLite's result codes (`BUSY`, `IOERR`, `CANTOPEN`, `NOTADB` — the vendored driver reports `SQLiteException` with a null SQLState, so the state-based branches cannot see it; classification is by primary code, never a blanket "null SQLState means down"), h2's closed-connection codes (`90007` closed object, `90098`/`90121` closed database — h2 carries the code as BOTH SQLState string and vendor code, outside the SQL-standard class range), and the DuckDB/SQLite JDBC-layer closed-connection lifecycle messages (both drivers report a closed connection as a plain `SQLException` with null state and code 0 — the message is the only discriminator, and the exact message + exact plain class keeps native errors out). Any other `SQLException` from a **metadata read walk** is a defect in this module or a driver bug and propagates as-is rather than being masked as "database unreachable". The **current-schema read** inside columns() is classified at the same place with the same family: feature-unsupported (the typed exception or SQLState `0A000`) reads as "driver reports none"; connection loss is the 502 path above; anything left (a non-connection failure of the read itself — pgjdbc's `getSchema()` executes `select current_schema()` on the server, so a statement cancel `57014` or permission error lands here) is the catalogued `pipeline.execution.parameter_required` with the driver exception attached as cause — never a raw rethrow to either surface. Driver text never reaches the wire (the caller can run the §8.1 connection test for the scrubbed failure detail).
- Credentials are never part of any introspection payload — the operations read schema metadata only.

`quoteIdentifier` has a second consumer since 087: the executor's **write-back** identifiers ([DAG Executor §6.4.3](dag-executor.md#643-outputtarget-datasource--write-back)) route through it instead of quoting `"…"` unconditionally — the SQL standard's spelling, which MySQL rejects without `ANSI_QUOTES` and MSSQL spells with brackets.

Surfaces: REST `GET /api/v1/datasources/{name}/schemas`, `/tables`, `/tables/{table}/columns` ([REST API §9.7](rest-api.md#97-schema-introspection)); MCP `datasources_get_schemas`, `datasources_get_tables`, `datasources_get_columns` ([MCP §6.2.16–18](mcp-server.md#6216-datasources_get_schemas)). The two statement-executing siblings are MCP-only: the catalog-statistics read `datasources_get_table_stats` (§7C) and the classified free-SQL probe `sql_probe` (§7D).

---

## 7C. Catalog table statistics

Shipped in 107 as the MCP tool `datasources_get_table_stats` ([MCP §6.2.33](mcp-server.md#6233-datasources_get_table_stats); MCP-only — no REST twin). One table's statistics, **always from the engine's OWN catalog** — `pg_class`, `information_schema`, `sqlite_stat1`, Parquet footers — and **never from scanning the table**: there is no `COUNT(*)` and no `COUNT(DISTINCT)` anywhere on this path, by construction, so the read is safe at any table size. It rides the same `SchemaIntrospector`/registry-pool lease boundary as §7A (same visibility gate, same `datasource.not_found` / `pipeline.execution.datasource_unreachable` translations), with the dialect's per-engine recipe declared as a `TableStatsPlan` on the adapter (`TableStatsPlans`).

**Payload:** `{row_estimate?, stats_as_of?, stats_source, indexes: [{name, columns, unique, primary, kind}], columns: [{name, n_distinct?, distinct_is_ratio, null_fraction?, min?, max?}]}`. The stat fields follow the envelope's omitted-when-null convention — a missing `row_estimate` asserts "the catalog does not hold this", never a zero. `stats_source` names the catalog the numbers came from, or `"none"` — a valid answer, not an error. A LAKE table has no indexes; its registered partition column IS the access structure the engine prunes on, so it reports as a pseudo-index `{name: "partition", kind: "partition"}`.

**Per-dialect support** (each row names the catalog the numbers come from):

| Dialect | row_estimate | stats_as_of | indexes | column stats | stats_source |
|---|---|---|---|---|---|
| POSTGRES | `pg_class.reltuples` (`-1` = never analyzed → null) | `greatest(last_analyze, last_autoanalyze)` | `pg_index`/`pg_attribute` in key order (`unnest(indkey) WITH ORDINALITY` — never a parse of `pg_indexes.indexdef`); expression-index columns drop out | `pg_stats`: `n_distinct` (a NEGATIVE value is a ratio of the table, normalized against the row estimate — `distinct_is_ratio` travels when it cannot resolve), `null_frac`, `histogram_bounds` first/last as min/max (the `anyarray` column travels as text; unnesting it is refused outright) | `pg_class` |
| MYSQL | `information_schema.tables.table_rows` (InnoDB's estimate) | — | `information_schema.statistics` in `seq_in_index` order; functional key parts (null column) drop out | none (`information_schema.column_statistics` histogram JSON has no reliably typed min/max — deliberately not read) | `information_schema.tables` |
| H2 | `information_schema.tables.row_count_estimate` | — | `information_schema.indexes`/`index_columns` | none | `h2_information_schema` |
| DUCKDB | `duckdb_tables().estimated_size` | — | `duckdb_constraints()` (PRIMARY KEY / UNIQUE, ordered key arrays) merged with `duckdb_indexes()` (explicit `CREATE INDEX`; columns parsed conservatively from the `expressions` text — a computed expression names no column and is dropped) | none | `duckdb_tables` |
| SQLITE | `sqlite_stat1`'s leading stat token — **post-ANALYZE only**: the catalog table does not exist until the first `ANALYZE`, and that absence is absorbed into a null estimate | — | `pragma_index_list`/`pragma_index_info` (an `INTEGER PRIMARY KEY` rowid alias has no index — none is reported, which is the truth of the storage engine) | none | `sqlite_stat1`, falling back to `none` when the catalog does not exist |
| ORACLE | `all_tables.num_rows` | `last_analyzed` | `all_indexes`/`all_ind_columns`, primary flag via `all_constraints` | none | `all_tables` |
| MSSQL | summed `sys.partitions.rows` over the heap/clustered allocation (`index_id IN (0,1)` — summing every partition would count nonclustered copies) | — | `sys.indexes`/`sys.index_columns` in `key_ordinal` order (included columns dropped) | none | `sys.partitions` |
| LAKE (parquet) | sum of `parquet_metadata` row-group `num_rows` over DISTINCT (file, row group) — footer reads, one row per (file, row group, column), so a naive sum multiplies by the column count | — | the partition pseudo-index (`kind: "partition"`) from the registry's `partition_column` | `null_fraction` (summed null counts over value counts; a writer omitting null counts makes it unknowable — null, not a guess) and numeric-aware min/max from `stats_min_value`/`stats_max_value` (the catalog types them VARCHAR; the comparison is done in Kotlin because SQL's MIN/MAX would compare `"100" < "9"` lexically) | `parquet_metadata` |
| LAKE (iceberg) | — | — | the partition pseudo-index is still reported | none | `none` — the registered location is the table's metadata JSON, and the DuckDB 1.5.x iceberg extension exposes no per-column statistics a footer-style read can reach; a documented gap |

Rules:

- **An unknown TABLE is empty stats, not an error** — the §7A filter philosophy: the stat fields are null/absent, `indexes`/`columns` empty, and `stats_source` carries the dialect's own label (`none` when even the catalog is absent). An unknown DATASOURCE is `datasource.not_found`.
- **The statement timeout is clamped to 10 s** — the datasource's own `query_timeout_seconds` applies only when TIGHTER. Catalog reads never scan a table — that is the whole point of the section — so they must not wait on a table scan's budget. The one exception is the lake's footer read, clamped to **60 s**: it is still metadata (no data pages are read), but a day-partitioned table is hundreds of Parquet footers over object storage — the demo's two-year `hvfhv_trips` (~700 files, unsigned S3) does not answer inside the catalog bound (measured live, 107). Footer rows are bounded at 65,536 so a million-file glob cannot stream its catalog through the wire.
- **Namespace routing is §7A's**: the caller's `namespace` filter, else the connection's current namespace — and a datasource reporting no current schema fails with `pipeline.execution.parameter_required` exactly like the columns read. A filter deeper than the dialect's namespace names no real place and answers empty stats. A lake's unfiltered read resolves only when exactly one namespace is registered; several refuse the same way.
- **Scope: `read`** on the MCP surface — the engine's own stored ESTIMATES about shape, never customer row data (the `templates_used_by` reasoning, [Auth §7.6](auth.md#76-scope--operation-matrix-authoritative)) — which is why it sits below the §7A tools' `author` floor.
- The LAKE location reaches `parquet_metadata(...)` only after the registry's location grammar is re-checked at the SQL-emission boundary (`s3://`/`file://`, no quotes/whitespace/control characters — the same total grammar the view generation enforces); JDBC-path identifiers are prepared-statement binds, never interpolated.

## 7D. The SQL probe

Shipped in 107 as the MCP tool `sql_probe` ([MCP §6.2.34](mcp-server.md#6234-sql_probe); MCP-only). The bounded, read-only free-SQL probe: **ONE** classified `SELECT`/`WITH` against a datasource, row-capped and timeboxed, answering rows + the canonical column schema + `wall_ms` of query time + the EXPLAIN plan captured BEFORE the query ran — a debug probe for authoring, not an export. `tempdb` is refused as a datasource (`pipeline.node.standalone_execution_refused`, `details.reason: "tempdb_source"`) — the staging database exists only inside a full execution; use `pipelines_execute`.

**The gate runs before anything is leased.** There is deliberately no general SQL parser in this repo, so `SqlStatementClassifier` is a conservative token scan with one job: admit exactly one `SELECT`/`WITH` statement, and nothing carrying a statement or session verb that can write, mutate session state, or shell out to the engine — `INSERT UPDATE DELETE MERGE UPSERT REPLACE DROP CREATE ALTER TRUNCATE GRANT REVOKE DENY CALL EXEC EXECUTE COPY ATTACH DETACH INSTALL LOAD PRAGMA SET USE VACUUM ANALYZE EXPLAIN IMPORT EXPORT CHECKPOINT INTO`. (`INTO` earns its place one step past a plain keyword list: `SELECT ... INTO t` is the one statement that STARTS with `SELECT` and still writes. `EXPLAIN`/`ANALYZE` are denied because the probe wraps the statement itself and a nested one would defeat the timebox accounting.) The scan strips line comments (`#` on MySQL only — elsewhere `#` is an operator), nested block comments, quoted literals (MySQL backslash escapes included) and Postgres/DuckDB dollar-quoted strings first, so a denylisted word inside a string or comment never trips the gate and a `;` inside one never counts as a statement separator. **Refusals are conservative BY DESIGN: false positives err toward refusal** — a column named `merge` trips the denylist — and the inverse gap is documented, not hidden: a statement-shaped trick the scanner cannot see through (a side-effecting function call such as `pg_terminate_backend`) is NOT caught. The gate is a shape check, not a side-effect proof; the datasource's own DB-user privileges remain the last line (§5.7).

**The plan comes first.** The dialect's EXPLAIN wrapper runs on the SAME lease, BEFORE the query, with the same binds and the same timeout (a plan can depend on the values) — so a timed-out probe still answers with the plan that explains it. Any EXPLAIN failure (unsupported shape, driver refusal, its own timeout) degrades to a null `plan` rather than failing the probe: the plan is best-effort, never the point of failure. `plan` carries `{scan?, estimated_rows?, raw, partitions_scanned?, partitions_total?}` — `scan` is the short per-dialect access description (`seq`, `index:<name>`, `partition_prune x/y`), `raw` the full plan text capped at 4096 chars (256 result rows), and every field but `raw` is a best-effort extraction that is omitted-when-null.

| Dialect | EXPLAIN form | What the summary reads |
|---|---|---|
| POSTGRES | `EXPLAIN <sql>` | root `QUERY PLAN` line: `seq` for `Seq Scan`, `index:<name>` for `Index [Only] Scan using`; row estimate from the root's `rows=N` |
| MYSQL | `EXPLAIN <sql>` | first row's `type` (`ALL` → `seq`; else `index:<key>` when a key is read) and `rows` estimate |
| H2 | `EXPLAIN <sql>` | the access path rides in the rewritten statement's `/* ... */` comment — `/* PUBLIC.TRIPS.tableScan */` → `seq`, `/* PUBLIC.IDX_CITY: CITY = 'x' */` → `index:PUBLIC.IDX_CITY`; no row estimate |
| DUCKDB / LAKE | `EXPLAIN <sql>` | the box-drawing plan text; the pruning marker is `Scanning Files: x/y` → `partition_prune x/y` with `partitions_scanned`/`partitions_total`; row estimate from the root's `~N rows` annotation. With no static file filter the marker is ABSENT (and a filter selecting every file is optimized away entirely) — the partitions fields stay null, the honest "no pruning information", never `y/y`. DuckDB 1.5.5.1 still prunes through `CAST`/`UPPER` on the partition column (verified) — a function on the column does NOT defeat the pruning this marker reports |
| SQLITE | `EXPLAIN QUERY PLAN <sql>` | the `detail` column: `SCAN t` → `seq`, `SEARCH t USING [COVERING] INDEX <name>` → `index:<name>`; no row estimate |
| ORACLE / MSSQL | none — `plan: null` | `EXPLAIN PLAN FOR` writes a plan TABLE and `SHOWPLAN` needs session state; neither is a read, so no wrapper is declared |

**Parameters are the pipeline grammar's.** `:name` placeholders bind through the same Spring `NamedParameterUtils` binder pipeline SQL uses, so probe SQL parses `:name` exactly the way a template's rendered output does; every referenced name must be supplied in `parameters` with its canonical type (`type` is a LogicalType — `NULL` is not declarable — and `value` its wire string: BIGINTEGER/BIGDECIMAL as decimal text, temporal in ISO forms, BINARY as padded base64; a null value binds SQL NULL). A reference with no supplied value is refused, never bound as null silently; a value that does not parse as its declared type is refused; both are `-32602` argument faults — nothing was leased, nothing ran — and the refusal names the parameter, never the value text.

**The clamps.** `limit` defaults to 50, clamps to 500 (not refused — a probe asking for more is a sizing error, and `truncated` already says the rest exists); `timeout_seconds` defaults to 10, clamps to 30, and is the statement timeout. A driver timeout arrives as `SQLTimeoutException` OR as a server-side cancel (pgjdbc delivers `queryTimeout` as SQLState 57014, a plain `PSQLException`) — both classify as the timeout, surfaced as the catalogued `pipeline.node.query_execution_failed` with `details` carrying `reason: "timeout"`, `wall_ms` and the pre-captured plan. Any other driver refusal is the same code with the bounded driver message; an unreachable datasource is `pipeline.execution.datasource_unreachable`.

**Scope: `author`** — the probe returns arbitrary customer ROW DATA, the 037 F rule it shares with `datasources_preview_rows`. **Audit:** the `sql` argument never reaches the audit log — the dispatcher records `sql_sha256` + `sql_length`, never the text ([MCP §14](mcp-server.md#14-audit)).

---

## 8. Connection Testing

### 8.1 `POST /api/v1/datasources/{name}/test`

```json
// Response (200 OK):
{
  "schema_version": 1,
  "correlation_id": "uuid",
  "data": {
    "connected": true,
    "server_version": "PostgreSQL 16.2 on x86_64-pc-linux-gnu, ...",
    "tested_at": "2026-08-05T14:30:00Z",
    "latency_ms": 23
  }
}
```

Or on failure:

```json
{
  "schema_version": 1,
  "correlation_id": "uuid",
  "data": {
    "connected": false,
    "tested_at": "2026-08-05T14:30:00Z",
    "error": "Could not acquire connection: Connection refused.",
    "error_class": "java.net.ConnectException"
  }
}
```

Note: connection test failure is **not** an HTTP error. The caller asked "can I connect?" and got an honest answer. HTTP 200 always (provided the datasource exists). The `data` object is the wire form of `TestResult` (§6.1).

### 8.1B The last test's outcome is stored, and listed

A connection test writes its outcome to the datasource row: `last_test_at`, `last_test_ok` and
`last_test_message` (metadata-db §4.10, migration V9). Every read path projects them as the
additive `last_test` object (§3.2), and the datasources screen renders them as a column — an
`ok` / `failed` badge, the timestamp, and the driver's message.

**Why this exists.** Listing a datasource does not connect to it. On 2026-09-02 every
multi-node demo pipeline failed at CONNECT with `password authentication failed for user
"dp_demo_ro"` while the datasources screen showed `sample-trips` as fine — there was no surface
on which a datasource could be *wrong*, only surfaces on which it could be *silent*. This is the
column that would have said "auth failed since 2026-08-30".

Three paths write it, all through the same probe (§8.1): `POST /datasources/{name}/test`, the
UI's Test button, and the §8A.3 rule-3 bootstrap credential check. `last_test_message` carries
the driver's own sentence on failure (the DEEPEST cause with text — HikariCP's "Connection is
not available, request timed out" names no database and diagnoses nothing) and the server
version on success. It is redaction-scrubbed at the probe like every other `TestResult` message,
so it is safe on the wire and on the screen.

**The write is an observation, not an edit.** It touches those three columns and nothing else —
in particular it does **not** move `updated_at`. That is the single documented exception to
metadata-db §2's "every UPDATE sets `updated_at`", and it exists so §8A.3 rule 1's promise (an
operator's row survives a boot byte-untouched) stays a mechanical check rather than a claim.

This is **not** a health poller. It records the outcome of tests that already happen; the
scheduled polling of §8.3 remains v1.1+.

### 8.2 Pre-execution check

Before pipeline execution begins, the executor pre-checks that every datasource referenced by the pipeline's nodes is configured and reachable. Failures here abort before any node runs, with error code `pipeline.execution.datasource_unreachable` (registered in the central catalog, [Pipeline Contract §13.8](pipeline-contract.md#138-datasource) — HTTP 502).

Pre-check is a fast `SELECT 1` (or dialect-equivalent) against each datasource. Cost: milliseconds per datasource, parallelizable.

### 8.3 Background health checks (optional)

In v1.1+, the system can poll datasources on a schedule and surface health in the UI. Not in v1.

---

## 8A. Bootstrap registration (config-declared datasources)

A deployment can declare datasources in a file and have the app register them at startup:
[`datapipelines.bootstrap.datasources-file`](configuration.md#318-bootstrap), unset = off. The
mechanism is generic (IaC-style environment setup); the datapipelines.co demo is its first user,
registering the sample databases as `global` + `readonly`.

### 8A.1 File shape

The `POST /api/v1/datasources` field vocabulary of §3.1, plus two flags. Unknown keys are a
startup refusal, not a silent skip — a mistyped `jdbc_ur` would otherwise register a datasource
with no URL that fails at first query.

The credential follows §3.4: a `credential: {kind, username?, secret?}` block, or the legacy
top-level `username`/`password` pair which means `kind: password` and keeps every pre-087 file
working. Declaring BOTH is a startup refusal rather than a precedence rule — they can disagree, and
no silent winner is defensible. Declaring NEITHER is a refusal too, naming both shapes: that is the
shape of a file whose `password:` key was mistyped, and it must not quietly become "no credential".

```yaml
# /etc/datapipelines/bootstrap-datasources.yml
datasources:
  - name: sample-trips
    display_name: "NYC Taxi Trips (sample)"      # optional; defaults to `name`
    dialect: POSTGRES
    jdbc_url: jdbc:postgresql://postgres:5432/dp_sample_trips
    credential:                                  # §3.4
      kind: password
      username: dp_demo_ro
      secret: ${SAMPLE_PG_PASSWORD}
    readonly: true                               # writes `is_readonly` (§5.7)
    global: true                                 # required, and must be true in v1
  - name: sample-reference
    dialect: SQLITE
    jdbc_url: jdbc:sqlite:/srv/sample/nyc_reference.db
    # SQLite is a FILE: no server, no login, and the xerial driver ignores a
    # username and password entirely. Until 087 this entry carried
    #     username: "sqlite"
    #     password: "sqlite-file-datasource-has-no-authentication"
    # not because anything used them but because the contract had no way to say
    # "there is no credential": §9's `datasource.validation.password_missing`
    # rejects a null-or-empty password on create, and bootstrap registration runs
    # the full §9 validation with no startup shortcut (§8A.3) — an empty password
    # fail-fasted startup with "A password is required on create." (boot-verified,
    # T38). `credential.kind: none` (§3.4) is that way, and V13 stores it as a NULL
    # `credential_encrypted`.
    credential:
      kind: none
    properties:
      jdbc:
        open_mode: "1"                           # xerial read-only open mode — see §8A.4
    readonly: true
    global: true
```

`readonly: true` writes `datasources.is_readonly`, so every §5.7 enforcement layer applies to a
bootstrapped datasource exactly as to one created over the API. `global: true` means
`workspace_id NULL`.

**`global` is required and must be `true`.** The flag is written explicitly rather than defaulted
because registration runs before any workspace exists, so there is no answer to "which workspace
does a non-global entry bind to". `global: false` and a missing `global` are both parse-time
refusals, with different messages; requiring the word means a file written for a later version
cannot be silently read as global by this one.

**A LAKE entry's registry seed (089 §E).** A `dialect: LAKE` entry may additionally declare the
dp-catalog rows its datasource serves (§8C.1), in ONE of two forms:

- **`tables:`** — inline registry rows, each the [rest-api §9.8](rest-api.md#98-lake-tables-the-dp-lake-catalog)
  register shape: `{namespace?, name, format, location, partition_column?}`.
- **`import_manifest:`** — the URL of an 088 `manifest.json` whose `tables[]` the bootstrap
  imports through the registry service's IDEMPOTENT path, so a re-boot re-asserts the registry
  instead of duplicating it. The fetch is server-side and passes the same SSRF boundary as
  `POST …/tables/import`: restricted to the datasource's own `dialect.endpoint` /
  `catalog.ref`, or a plain AWS S3 host — never an arbitrary URL.

Two qualifiers: **`namespace:`** is the shared namespace a seed row without its own lands in
(the demo's manifest carries no namespaces; its tables land in `[nyc, mobility]`), and
**`only_tables:`** filters an import to the named tables — the demo uses it to exclude the
manifest's Iceberg copy as demo scope, the Iceberg read path being proven by the integration
suite rather than the demo datasource. The parse-time refusals: both seed forms together
(they can disagree — the credential-shape rule); either form on a non-LAKE entry (a JDBC
database's tables are discovered, never registered — a `tables:` block under a Postgres entry
is a paste error); `namespace:` / `only_tables:` with no seed (they qualify a seed, they are
not one); and an explicitly EMPTY `tables:` block (a seed of zero tables is a mistake, not a
policy). The demo entry is [`deploy/sample-data/bootstrap-datasources-lake.yml`](../deploy/sample-data/bootstrap-datasources-lake.yml).

### 8A.2 `${ENV_VAR}` resolution

Secrets never live in the file. Every string in the tree — not just `password`; a passphrase is as
likely to sit in `properties.jdbc` — has its `${NAME}` placeholders resolved against the **process
environment** at read time. This is the app's own substitution, not Spring's: the file is runtime
data named by a config key, read from disk after the context is built, so `${...}` in it means
nothing to Spring's placeholder resolution.

A placeholder whose variable is not set is a startup refusal naming the variable. It is never left
as a literal, because a datasource registered with `${SAMPLE_PG_PASSWORD}` as its credential fails
much later, as an unintelligible authentication error against a database nobody suspects.

### 8A.3 Semantics: create-if-absent, never update

Applied once per startup, after Flyway and after the [Auth §4.4](auth.md#44-bootstrap-admin)
actor is resolved, before the server accepts traffic. `created_by` is that actor for every entry.

- **Create-if-absent by `name`.** An existing row is left byte-untouched and logged at INFO. This
  is the operator's guarantee: a restart never reverts an edit they made, and a datasource they
  deleted never resurrects itself. "Existing" includes **soft-deleted** rows — `name` is the
  primary key (§9 `duplicate_name`), so a soft-deleted name is permanently taken.
- **Full §9 validation per entry, test pool build (§5.4) included.** Registration goes through the
  same save path as the REST endpoint; there is no startup-only shortcut. Skipping the pool build
  because "it is slow at boot" would ship a demo that registers dead connections and discovers it
  at first query.
- **One entry at a time, fail-fast.** Entries are applied in file order, each fully validated and
  written before the next is attempted. The first failure refuses startup: a half-registered demo
  is worse than a loud one, and create-if-absent makes the operator's retry idempotent.
- **Rule 3 — a credential desync is reconciled by CONNECTING, never by preferring a source**
  (added 2026-09-03). For an entry whose row already exists AND whose file credential **differs**
  from the stored one — equal credentials are the ordinary boot and nothing is probed:

  | Probe result | What happens |
  |---|---|
  | the STORED credential authenticates | the row is left **byte-untouched**. Rule 1 stands: the operator's edit works, so it is kept |
  | stored fails, the FILE's authenticates | **the credential alone is updated** — not the name, the display name, the URL, the properties, the readonly flag or the binding — the pool is evicted, and a WARN names the datasource |
  | neither authenticates | the row is left alone and an ERROR names the datasource and the environment variable an operator would change |
  | the stored ciphertext cannot be DECRYPTED | the row is left alone and an ERROR names the encryption key. A credential that could not be READ is not one that failed a LOGIN, and overwriting it would paper over a key problem every other datasource shares |

  A **soft-deleted** row is never reconciled: rule 1 promises a deleted datasource never
  resurrects, and writing its credential back would be a resurrection in all but the flag.

  Startup does **not** fail on a broken credential — the app has to boot for an operator to be
  able to fix the row — so the ERROR line is the loud part. Every branch that probed records its
  outcome on the row (§8.1B), which is what puts the answer on the screen.

  **Why this is not "always update".** Always-update would revert an operator's edit on every
  boot, which is exactly what rule 1 exists to prevent. Rule 3's narrowness is entirely in the
  fact that a *probe*, not a *preference*, decides: it fires only on a difference, writes only on
  a proven failure paired with a proven success, and writes only one column.

  **The incident it closes.** `sample-trips` was registered on 2026-08-30.
  `SAMPLE_PG_PASSWORD` later changed; the sample database's role got the new password at the next
  load, the stored row kept the old one, and nothing compared them. A clean-machine rehearsal
  cannot see this class of defect at all — it exists only on the upgrade path.

Failures here are startup refusals, not API errors — nobody submitted a request — so they carry no
`datasource.validation.*` response code of their own. An entry that fails §9 raises that rule's
code in the startup log, exactly as it would have in a `400`.

### 8A.4 SQLite read-only open mode

`readonly: true` is a datapipelines-level flag (§5.7); making the SQLite *driver* open the file
read-only is a `properties.jdbc` key, and the two are independent — set both for a sample database.

The key is **`open_mode: "1"`** (`1` = `SQLiteOpenMode.READONLY`), verified against the pinned
driver `org.xerial:sqlite-jdbc:3.49.1.0` rather than recalled: with it, `Connection.isReadOnly()`
is true and both `INSERT` and `CREATE TABLE` fail `SQLITE_READONLY`. The similarly-named
`jdbc.explicit_readonly` is **not** a read-only switch — with it alone the same writes succeed; it
only makes an explicit `Connection.setReadOnly(true)` meaningful. Re-verify the key against the
driver on any xerial upgrade.

### 8A.5 DuckDB read-only open mode

The DuckDB analogue of §8A.4, used by the trade family's `sample-trade-us` entry. The mechanism
is the DuckDB config `access_mode = READ_ONLY`, delivered as a **connection property** — verified
against the pinned driver `org.duckdb:duckdb_jdbc:1.5.5.1` (2026-09-04), two probed facts:

- the **URL-parameter form** (`jdbc:duckdb:…?access_mode=READ_ONLY`) is **silently ignored** — the
  connection opens read-write and writes succeed;
- the **connection-Properties form** (`properties.jdbc.access_mode: READ_ONLY`) is honored: writes
  on an existing file fail, and opening a read-only connection to a file that does not exist fails
  at connect ("Cannot open database … in read-only mode: database does not exist") — so the loader
  must place the file before the app registers the datasource, which the demo profile's service
  ordering guarantees.

`access_mode` is not in the DUCKDB §5.6 refusal set (only the five extension keys are), so the
bootstrap entry may set it. The demo additionally mounts the sample volume read-only into the
app container — the filesystem-level backstop under the driver lock and the §5.7 flag.

---

## 8C. dp-lake — the catalog, the per-table views, and registry introspection

**dp-lake** is the offering built on the `LAKE` dialect (§4.1): Parquet and Apache Iceberg
tables on S3 or S3-compatible object storage, queried **in place** — no warehouse, no load
step, nothing copied. DuckDB is the engine underneath (an in-memory instance per pooled
connection); the catalog is **dp-catalog**, our own minimal registry, because the engine
cannot LIST a bucket. A LAKE datasource is a READ connector: nothing here writes to the
object store.

### 8C.1 dp-catalog — the registry

A LAKE datasource's tables are exactly the rows of `lake_tables` (V15; [metadata-db §4.15](metadata-db.md#415-lake_tables))
— the registry IS the catalog. `datasource_id` holds the datasource **name**, which is the
`datasources` primary key; there is no surrogate id to point at.

- **`namespace`** — 1–9 segments, each following the pipeline/template segment grammar
  **without `.`** (the dotted shorthand on the wire must round-trip). The view mapping
  (§8C.2) supports one or two segments today; deeper namespaces are registered, recorded
  with a `last_error` and skipped at connect rather than being silently flattened.
- **`name`** — one segment of the same grammar. **`format`** — `parquet` | `iceberg`
  (CHECK-constrained: a third value would generate bad view SQL later).
- **`location`** — `s3://bucket/prefix[/glob]` or a `file://` path; **no other schemes**, and
  no quotes, backslashes, whitespace or control characters anywhere — the value is later
  interpolated into the engine's `CREATE VIEW`, so the refusal is total (a value that would
  need escaping is refused, never escaped). Iceberg tables are registered by their current
  metadata FILE — the measured rule is §8C.7.
- **`partition_column`** — optional; the column the engine's partition pruning keys on.
- **`last_error` / `last_error_at`** (V22) — the connect-time view creation's recorded
  failure and when it was newly recorded (109 §A). NULL/NULL is the healthy spelling;
  recording is transition-only, so `last_error_at` reads as "broken since". Maintained by
  the pool's view application alone; cleared by the next successful view creation.

**Registration pre-flight (109 §A).** `register` and `importTables` prove a candidate table
READABLE before its row is stored: on a scratch connection built exactly like the
datasource's pool (same adapter init, same bundled-extension posture), the table's view is
created and one row is scanned through it. A failure refuses the write with
`datasource.validation.lake_table_unreadable` (400) carrying the engine's error text,
bounded to 2000 characters — a table that cannot be read is never stored to fail every
connect after. Import pre-flights only the triples not already registered: an idempotent
re-import (bootstrap re-runs it on every boot) neither re-reads nor fails on tables that
are already registered.

The surface:

- **REST** ([rest-api §9.8](rest-api.md#98-lake-tables-the-dp-lake-catalog)):
  `POST /api/v1/datasources/{name}/tables` (register one), `DELETE …/tables/{ns}/{table}`,
  `POST …/tables/import` (an inline `tables[]` block, or a `manifest_url` fetched server-side
  and restricted to the datasource's OWN bucket/endpoint — the SSRF boundary), and
  `GET …/lake-tables` (the registry's own rows, `read` scope). The writes are `author`;
  mutating a GLOBAL datasource's registry is admin-only as a workspaces D8 rule.
- **MCP**: `lake_tables_register`, `lake_tables_import`, `lake_tables_unregister`
  ([mcp-server §6.2.29–31](mcp-server.md#6229-lake_tables_register)) over the same
  application service, so validation, the duplicate refusal and the pool invalidation are
  identical on both surfaces.
- **UI**: a LAKE datasource's detail page shows the registry as a READ-ONLY namespace tree
  with each table's format and partition column. Registration stays REST/MCP — authoring is
  agent-first by design.

Every successful registry write **evicts the datasource's connection pool and publishes the
§5.7 invalidation** (Redis channel `dp:datasource-invalidated`): every instance rebuilds the
pool on the next lease — so a table registered on instance A is visible on instance B's next
execution — and registry-backed introspection (§8C.3) catches up within its 60 s cache TTL.

### 8C.2 A view per registered table, built at connect — and isolated per table

A pooled connection on `jdbc:duckdb:` / `jdbc:duckdb::memory:` is its OWN in-memory DuckDB
instance (verified 2026-09-07 against duckdb_jdbc 1.5.5.1: objects created on one connection
are invisible to a second, while both are open). Every new physical connection therefore
builds its own catalog/schema/view set — which is also why nothing here can leak across
datasources.

**Per-table isolation (109 §A).** The views do NOT ride HikariCP's `connectionInitSql` — one
string, no try/catch, so a single failing `CREATE VIEW` (a bad prefix, a wrong format, a
file that is not Parquet) used to fail the whole pool build and make EVERY registered table
unreachable. Instead the pool's driver DataSource is wrapped (`LakeViewApplyingDataSource`):
on each new physical connection the adapter's own `connectionInit` statements (§4.2A) and
the shared prelude (extension loads, ATTACHes, schema creations) run STRICTLY — a failure
there is a datasource fault and fails the connect as before — and then each table's view
runs **independently**: a failing view is caught, recorded on the table's registry row
(`last_error` / `last_error_at`, bounded to 2000 characters) and **skipped**, and the
connect succeeds with the surviving views. The same isolation covers the SQL-emission
boundary's own refusals (an unmappable 3+-segment namespace, a location that fails the
grammar, an unknown format): the refusal is captured per table and recorded, never thrown
into the pool build. Recording is **transition-only** — the applier compares against the
state the pool was built with, so an unchanged outcome writes nothing and the hot path
carries no per-connection write; a success after a failure CLEARS the row's `last_error`.

**The broken table at query time.** A node whose rendered SQL references a table with a
recorded `last_error` fails at the CONNECT phase with the catalogued
`datasource.lake.table_unavailable` (502), `details` carrying `table` and `last_error` —
the recorded reason — instead of the engine's raw "table not found". The detail page's
registry tree and `GET …/lake-tables` show the broken table with its recorded error (a
`view failed` badge carrying the text on hover).

The **namespace mapping** follows DuckDB's exact three-level object space
(`catalog.schema.object`; its parser refuses a deeper `CREATE SCHEMA` outright):

- **two segments** `["nyc", "mobility"]` — the head becomes an in-memory ATTACHed catalog
  (`ATTACH IF NOT EXISTS ':memory:' AS "nyc"`, the one writable catalog a view needs to live
  in) and the rest a schema inside it.
- **one segment** `["nyc"]` — a schema in the connection's default catalog; no ATTACH.
- **three or more** — recorded as the table's `last_error` (the
  `datasource.validation.lake_namespace_invalid` message) and its view skipped: the engine
  cannot name the place, and flattening would alias two different registry namespaces onto
  one schema.

Then one view per registered row:

```sql
CREATE OR REPLACE VIEW "nyc"."mobility"."hvfhv_trips" AS
SELECT * FROM read_parquet('s3://bucket/hvfhv_trips/pickup_date=*/part-*.parquet', hive_partitioning = true);
-- format = iceberg instead:
CREATE OR REPLACE VIEW "nyc"."mobility"."trips_iceberg" AS
SELECT * FROM iceberg_scan('s3://bucket/iceberg/trips/metadata/00002-….metadata.json');
```

`hive_partitioning = true` is emitted UNCONDITIONALLY for Parquet: it is harmless on a
non-partitioned layout (a plain file reads normally), and making it conditional on
`partition_column` would let a registered-but-wrong partition column silently disable pruning
for a table that IS partitioned. The engine's partition pruning then applies to the
underlying `read_parquet` scan unchanged — a predicate on the partition column reads only the
partitions it names.

**The search-path rule — when bare table names resolve.** When ALL of the datasource's
registered tables share EXACTLY ONE distinct namespace, the last statement is
`SET search_path = '<catalog>.<schema>'` (verified to resolve bare table names on duckdb_jdbc
1.5.5.1; the catalog-qualified spelling cannot drift into a same-named schema elsewhere).
**This is the documented choice, and the demo uses it**: the `sample-lake` datasource's four
tables all live in `[nyc, mobility]`, so templates read `FROM hvfhv_zone_day` bare. With ZERO
or MULTIPLE distinct namespaces nothing is set — there is no defensible default — and queries
must use the fully qualified `catalog.schema.table` form.

### 8C.3 Introspection reads the registry, not JDBC metadata

For a LAKE datasource the §7A operations are served from dp-catalog (round 089 §C), with no
new wire fields — 087's `namespace` carries everything:

- **schemas** = the registry's distinct namespaces.
- **tables** = the registry's rows, each reported as JDBC type `VIEW` (what the engine made
  it — §8C.2) with its `format` in `remarks`.
- **columns** = a zero-row `SELECT * FROM <view> LIMIT 0` read through `ResultSetMetaData`
  and mapped by the adapter's DuckDB mapper exactly like a JDBC `getColumns` row — nested
  LIST/STRUCT/MAP arrive as STRING with the mapper's warning. (`DESCRIBE SELECT …` reports
  the same schema as STRINGS, so the mapper's DECIMAL precision/scale inputs would have to be
  re-parsed; the structured read is the faithful one.) With MULTIPLE registered namespaces an
  unfiltered columns read cannot pick one and fails with the §7A current-schema-unknown rule
  — pass the table's `namespace`, the same recovery as the JDBC path.

All three are cached per datasource for the §6.3 60 s TTL; on S3 the zero-row columns scan is
a footer read over the network, which is what the cache is for.

### 8C.4 Engine limits — compute is on the app's box

Every lake connection gets the engine limits as `SET` statements ([configuration.md §3.24](configuration.md#324-lake-datasource-engine-limits-dp-lake)
is the operator paragraph): `dialect.memory_limit` (default **25 % of the container's memory
as the cgroup-aware JVM reports it**, floored at 64 MiB and hard-capped at 4 GiB — an
explicit value is the operator's own number and is not capped), `dialect.threads`,
`dialect.temp_directory` when declared, and `preserve_insertion_order = false` ALWAYS —
insertion order costs memory and temp-file discipline the engine would otherwise spend on a
guarantee a read-only lake never asks for. DuckDB shares the box with the JVM, which is what
the default's cap exists for. The existing row cap and `node-query-timeout-seconds` apply
unchanged.

### 8C.5 Extensions, bundled in the image

A `LAKE` datasource whose data is on S3 needs DuckDB's `httpfs` and `aws` extensions, and the
Iceberg catalog kinds additionally need `iceberg` (which requires `avro`). Where the binaries
come from is the operator key `datapipelines.duckdb.extension-directory`
([configuration.md §3.25](configuration.md#325-duckdb-extension-directory-dp-lake)):

- **Set — the shipped image**: the published image bundles the four extensions for DuckDB
  core **v1.5.5** under `/opt/duckdb/extensions/v1.5.5/<platform>/` (+108 MB uncompressed),
  the `<platform>` following the image build's architecture (`linux_amd64` or `linux_arm64`
  via the Dockerfile's `TARGETARCH` mapping), and
  exports the variable from the Dockerfile. Every lake connection then runs
  `SET extension_directory` plus BARE `LOAD`s and **never an `INSTALL`** — in DuckDB v1.5.5
  `LOAD` strictly loads already-present files (no download code path runs at all, measured in
  the 089 §7.3 spike with `--network none`), so **a hardened dp-lake deployment needs no
  egress to `extensions.duckdb.org` at all**.
- **Unset**: the explicit `INSTALL`+`LOAD` pairs developer machines rely on — which requires
  egress to DuckDB's extension repository at connect time.

Independently of the declared `catalog.kind`, the view seam (§8C.2) loads the `iceberg`
extension **whenever the registry holds an iceberg-format table**, in either mode — a bare
`LOAD avro` then `LOAD iceberg` against the bundled directory, or `INSTALL iceberg` (which
pulls `avro` in as a dependency over the network) without one. `catalog.kind: s3` loads only
`httpfs`+`aws` on its own, and an Iceberg view would otherwise be the table that fails at
connect. Parquet-only registries load nothing extra: an extension nothing will call is
surface for nothing.

### 8C.6 Credentials and addressing

The S3 credential comes from the datasource's §3.4 credential, never from `properties.*`:

- **`credential.kind: none`** — the engine's `credential_chain` provider (IAM role,
  environment, shared config/profile). The self-hosted answer, with nothing to encrypt or
  rotate.
- **`credential.kind: password`** — an explicit key pair: the access key id as `username`,
  the secret as the stored credential, emitted as `KEY_ID`/`SECRET` in the connect-time
  `CREATE OR REPLACE SECRET`.
- **A PUBLIC bucket declares `dialect.unsigned: "true"`** and gets **no S3 secret at all** —
  the engine's reads go unsigned. This is not optional decoration: `credential_chain`
  VALIDATES at create time, so on a credentials-free box the `CREATE SECRET` fails and takes
  the pool down with it (found by the 089 live gate). `unsigned` beats every credential
  kind; the demo's `sample-lake` sets it.

`dialect.region` sets the secret's region; `dialect.endpoint` (host[:port], no scheme) points
at an S3-compatible store — MinIO, on-prem — and a declared endpoint always emits
`USE_SSL false` (MinIO and most on-prem S3 endpoints are plain HTTP; a declared endpoint is a
deliberate non-AWS target, so TLS is not assumed for it); `dialect.url_style` is `path` or
`vhost` for those endpoints. `dialect.catalog.kind` accepts
`s3` (what the demo and this round's suites use) and — accepted by the adapter since 087 —
`glue` | `s3_tables` | `rest`, which the registry does NOT read (§8C.8).

### 8C.7 The Iceberg location rule — register the metadata FILE

Measured 2026-09-08 against duckdb_jdbc 1.5.5.1 (the 089 §F MinIO suite): DuckDB resolves an
`iceberg_scan` location through `version-hint.text` by CONSTRUCTING `v<n>.metadata.json` /
`<n>.metadata.json` filenames, which never match the Iceberg spec's
`%05d-<uuid>.metadata.json` names pyiceberg actually writes — so scanning a table by its
**root** (the directory holding `metadata/`) fails, and only the explicit metadata file
scans. **Register the table's CURRENT metadata FILE as `location`**
(`s3://bucket/table/metadata/00042-<uuid>.metadata.json`). This contradicts the design
record's "the table root holding `metadata/`" — the tree won. The corollary for an Iceberg
table that still receives commits: every commit writes a new metadata file, so the registry
row must be re-registered to follow the table.

### 8C.8 Not in this round

- **External catalogs as a registry source.** `catalog.kind glue | s3_tables | rest` is
  accepted by the adapter (087) but dp-catalog does not read AWS Glue, S3 Tables or a REST
  catalog — a LAKE datasource's tables are exactly the registered rows (§8C.1). External
  catalogs remain a later, optional source.
- **Writes to the lake.** dp-lake is a read connector; there is no write path to the object
  store.
- **Scheduler, dashboards.** Pipelines run when executed (or when a published endpoint is
  called); nothing schedules them and no dashboard surface ships.
- **Nested types.** LIST/STRUCT/MAP columns introspect as STRING with the mapper's warning
  (§8C.3); they are queryable in SQL but not type-mapped.
- **Athena / Glue as engines.** Out by thesis — paid services; DuckDB on the app's box is the
  engine.

---

## 9. Validation Rules

Every rule below runs on **create and update**, before the row is written (§2 principle 7; [Pipeline Contract §2](pipeline-contract.md#2-design-principles)). All failures are collected, not short-circuited (§6.1 `ValidationResult`). HTTP mappings live in the central catalog, [Pipeline Contract §13.8](pipeline-contract.md#138-datasource).

| Code | Check |
|---|---|
| `datasource.validation.name_invalid` | `name` matches `[a-z0-9_-]+`, length 1–63 |
| `datasource.validation.dialect_invalid` | `dialect` is a value of the [Type System §5](type-system.md#5-source-to-canonical-mapping-tables) dialect set |
| `datasource.validation.jdbc_url_malformed` | URL parses, matches the dialect's expected pattern (`DialectAdapter.validateJdbcUrl`), and carries no server-managed, refused (§5.6), or credential key in its query/property segment |
| `datasource.validation.jdbc_url_scheme_invalid` | URL begins with `jdbc:{dialect}:` |
| `datasource.validation.password_missing` | The credential SECRET is required on create, for every `credential.kind` but `none` — and on an UPDATE that changes the kind, since keeping the stored secret would relabel it (§3.4). The code keeps its pre-087 spelling — the catalog ([Pipeline Contract §13.8](pipeline-contract.md#138-datasource)) is the authority for concrete codes and this rule did not gain one; its meaning is widened, not moved. |
| `datasource.validation.properties_invalid` | The **test pool build** (§5.4) succeeded: `properties.hikari.*` names/values are accepted by `HikariConfig`, `properties.jdbc.*` is a flat string map, no server-managed key (`jdbcUrl`, `username`, `password`, `driverClassName`, `dataSourceClassName`, `poolName`, `exceptionOverrideClassName`, …) is present under `hikari`, no refused key (§5.6) or server-managed/credential key is present under `jdbc`, and `properties` contains no namespace other than `hikari` / `jdbc`; and `introspection_include_schemas`, when present, lists exact schema names over the legal-identifier alphabet of the supported dialects — letters, digits, `_`, `$`, `#`, lowercase (entries outside the alphabet are rejected; §3.3). Also the §3.4 credential-shape rules: `credential.kind` is one this dialect's driver accepts, `username` is present exactly when the kind allows it, and `secret` is absent for `kind: none`; and a payload carrying both `credential` and the legacy `username`/`password` pair. The offending field and the underlying Hikari/driver message are returned in `details`. |
| `datasource.validation.query_timeout_invalid` | `query_timeout_seconds`, when present, is an integer ≥ 1 |
| `datasource.validation.duplicate_name` | Create with a name that already exists. `name` is the PRIMARY KEY ([Metadata DB §4.10](metadata-db.md#410-datasources)), so uniqueness is GLOBAL including soft-deleted rows — a deleted datasource's name is not reusable until hard-deleted (corrected 2026-08-08: pipelines reference datasources by name, so silent reuse would repoint history; consistent with pipeline `duplicate_name`). No reactivate-on-recreate path in v1. |
| `datasource.driver_not_loaded` | The JDBC driver class for `dialect` is on the classpath (§10.3) |

`datasource.driver_not_loaded` is deliberately **not** in the `datasource.validation.*` namespace: it reports a deployment/packaging state (a missing driver JAR), not a defect in the submitted entity — the same payload becomes valid after the operator rebuilds with the right profile. The code is canonical in [Enums §16](enums.md#16-error-code-domains-prefix-catalog) / [Pipeline Contract §13.8](pipeline-contract.md#138-datasource).

`datasource.in_use` (delete blocked by referencing pipelines, §6.2) is a lifecycle error rather than a save-time rule, and is likewise catalogued in §13.8.

---

## 10. JDBC Driver Packaging

### 10.1 The licensing problem

Some JDBC drivers have licenses that complicate redistribution in an OSS project:
- **Oracle ojdbc** — OTN license; redistributable on Maven Central but license terms bind the distributor.
- **MySQL Connector/J** — GPL-2.0 with FOSS exception; depends on the project's license compatibility.

### 10.2 Strategy

**Default distribution (clean licenses only):**
- Postgres, MSSQL, H2, DuckDB, SQLite ship in the default build.
- Default deployment can connect to PG, MSSQL, MySQL (if user supplies driver), H2, DuckDB, SQLite out of the box.

**Optional Gradle profile `-Poracle`** adds the Oracle driver as a dependency:
- Operator builds with `./gradlew -Poracle build` to include.
- Or: deploy-time, drop the JAR into `lib/` (Spring Boot's loader picks it up).

**Optional Gradle profile `-Pmysql`** does the same for MySQL Connector/J.

For self-hosted deployments where the operator has accepted the relevant licenses, both options work. Default build stays clean.

### 10.3 Driver class lookup

```kotlin
object JdbcDrivers {
    private val drivers = mapOf(
        Dialect.POSTGRES to "org.postgresql.Driver",
        Dialect.ORACLE   to "oracle.jdbc.OracleDriver",
        Dialect.MSSQL    to "com.microsoft.sqlserver.jdbc.SQLServerDriver",
        Dialect.MYSQL    to "com.mysql.cj.jdbc.Driver",
        Dialect.H2       to "org.h2.Driver",
        Dialect.DUCKDB   to "org.duckdb.DuckDBDriver",
        Dialect.SQLITE   to "org.sqlite.JDBC"
    )

    fun classNameFor(dialect: Dialect): String =
        drivers[dialect] ?: error("No driver mapped for dialect $dialect")

    fun isAvailable(dialect: Dialect): Boolean = try {
        Class.forName(classNameFor(dialect)); true
    } catch (e: ClassNotFoundException) {
        false
    }
}
```

At datasource create/update time, validation calls `isAvailable(dialect)` and rejects with **`datasource.driver_not_loaded`** if the driver JAR is missing. This check runs *before* the test pool build (§5.4) — a missing driver would otherwise surface as a confusing `properties_invalid`.

---

## 11. CRUD Operations

Wire contracts (envelopes, status codes, examples) live in [REST API §9](rest-api.md#9-datasource-endpoints); required scopes live in the [Auth §7.6 scope matrix](auth.md#76-scope--operation-matrix-authoritative) (read = `read`, test = `author`, create/update/delete = `admin`). This table is the operation inventory.

| Operation | Method & Path | Notes |
|---|---|---|
| Register datasource | `POST /api/v1/datasources` | Validates (§9) + test pool build (§5.4), encrypts password, stores. |
| List datasources | `GET /api/v1/datasources?dialect={d}` | Passwords never included. |
| Get datasource | `GET /api/v1/datasources/{name}` | Password replaced with `password_set: bool`. |
| Update datasource | `PUT /api/v1/datasources/{name}` | Password optional (omit to keep existing). `name` may not change (§11.1). Drains & rebuilds pool. |
| Delete datasource | `DELETE /api/v1/datasources/{name}` | Soft delete; fails with `datasource.in_use` if referenced. |
| Test connection | `POST /api/v1/datasources/{name}/test` | Returns a `TestResult` (§6.1); HTTP 200 even when `connected: false`. |

### 11.1 Rename semantics — `name` is immutable

`name` is the primary key **and** the reference pipelines carry in their JSON (§2 principle 1). Renaming it would silently break every pipeline pointing at the old value, in every environment, with no write-time signal — so **there is no rename operation**:

- `PUT /api/v1/datasources/{name}` ignores no field silently: a body whose `name` differs from the path segment is rejected with `datasource.validation.name_invalid` (`field: "name"`, message stating immutability). Every other field, including `dialect`, `jdbc_url`, credentials, `query_timeout_seconds` and `properties`, is updatable.
- A rename is therefore **delete + create**: create the new datasource, repoint the referencing pipelines (a new pipeline version per [Pipeline Contract §14](pipeline-contract.md#14-pipeline-lifecycle-operations)), then delete the old one.
- The delete step is the guard that makes this safe: it is **blocked while any non-deleted pipeline references the name** (`datasource.in_use`, §6.2), so the old datasource cannot disappear until the repointing is complete. Order matters — create → repoint → delete.

---

## 12. Stability Promise

### 12.1 Frozen in v1

- Datasource entity JSON shape, including the `properties` namespaces. **Amended additively in 087**: `hikari` and `jdbc` are unchanged and stay passthrough; a third reserved namespace `dialect` joins them, TYPED and adapter-validated rather than passthrough (§4.2A). The amendment is additive in both directions — a payload with no `dialect` block behaves exactly as before, and a `dialect` block on a dialect that declares no keys is refused rather than silently ignored, so nothing that used to work stops working and nothing that used to be rejected starts being accepted.
- The legacy top-level `username`/`password` credential pair, which means `credential.kind: password` (§3.4). Additive means additive: the `credential` block joined it, it did not replace it.
- `name` immutability and its role as the pipeline-facing reference.
- The supported dialects and their identifiers (8 as of 087 — `LAKE` joined non-breakingly under §12.2's "new dialects added non-breakingly").
- The separation of pipeline-name from connection-details.
- The encryption-at-rest requirement, and the single required key source (fail-fast).
- Save-time validation including the test pool build.

### 12.2 Not frozen

- Pool implementation (HikariCP) — could swap to AGPL-licensed alternatives if license concerns arise. Note this would change the meaning of `properties.hikari.*`; a swap therefore requires a migration path, which is why the namespace is named after the pool rather than being generic.
- The exact encryption scheme (AES-256-GCM today; KMS as an additional explicit key source in v1.1 — [ROADMAP §2](ROADMAP.md#2-v11-candidates)).
- New dialects added non-breakingly.
- Which pool/driver properties are *useful* — the passthrough model means no spec change is needed to adopt new ones.

---

## 13. Implementation Notes

### 13.1 Where this lives

`datasources` Gradle module:

- `co.datapipelines.datasources.Datasource` data class
- `co.datapipelines.datasources.Dialect` enum
- `co.datapipelines.datasources.DatasourceRegistry` — service interface
- `co.datapipelines.datasources.JdbcDrivers` — driver class lookup
- `co.datapipelines.datasources.pooling.ConnectionPoolManager` — HikariCP wrapper
- `co.datapipelines.datasources.crypto.CredentialEncryptor` — AES-256-GCM
- Per-dialect: `PostgresDialectAdapter`, `OracleDialectAdapter`, etc.

### 13.2 Testing

- Unit tests per `DialectAdapter` covering `validateJdbcUrl` and `buildHikariConfig`.
- Passthrough tests: a valid `properties.hikari` entry reaches `HikariConfig` verbatim; an unknown key, a wrong-typed value, and a server-managed key each fail the test pool build with `datasource.validation.properties_invalid` and name the offending key.
- Test pool build does **not** require a reachable database (`initializationFailTimeout = -1`): saving a datasource pointing at a dead host succeeds; `POST .../test` on it returns `connected: false`.
- Pool concurrency: N coroutines calling `poolFor()` simultaneously for a cold datasource construct exactly one `HikariDataSource`.
- Query-timeout precedence: datasource `query_timeout_seconds` set → used; unset → global `node-query-timeout-seconds`.
- Immutability: `PUT` with a differing `name` is rejected; delete while referenced returns `datasource.in_use` with the pipeline list.
- Startup: a missing / malformed / wrong-length `DATAPIPELINES_DB_ENCRYPTION_KEY` fails application startup (no fallback path exists to test).
- Integration tests via Testcontainers: spin up real PG/MySQL/MSSQL/Oracle containers, register a datasource, test connection, run a query, verify type mapping matches [Type System §5](type-system.md#5-source-to-canonical-mapping-tables).
- Credential encryption tests: encrypt/decrypt round-trip, key-rotation flow, tamper detection (auth tag failure).
- Connection pool tests: lease timeout, max-pool-size enforcement, eviction on datasource delete.

---

## 14. Open Questions / Future Additions

Out of scope for v1 (v1.1 candidates are tracked in [ROADMAP §2](ROADMAP.md#2-v11-candidates)):

- **KMS integration**: AWS KMS / GCP KMS / HashiCorp Vault as an additional **explicit** master-key source (never an implicit fallback — §7.1).
- **Background health checks**: scheduled polling of datasources with UI health indicators.
- **Pool settings beyond the eight (§5).** The dialog offers the eight keys a person tunes; the rest of HikariCP stays REST-only passthrough. A dialect that wants its own pool defaults has the seam (`DialectAdapter.defaultHikariProperties`) and no shipped dialect uses it yet — an embedded engine wanting a single-connection pool is the first plausible caller.
- **A retirement view.** `datapipelines.datasource.pool.retired` / `.hard_closed` (§5.2) are counters; there is no surface that lists what is currently draining on this instance. The reaper's state is in memory and per-instance, which is the right place for it, but it means "why is this pool still open" is answered from logs today.
- **Datasource groups / failover**: pair primary + replica, fail over on connection failure.
- **Read-only enforcement**: some datasources should be read-only by contract (we never write to sources, but enforcing at the datasource level adds defense).
- **SSH tunnel / bastion host support**: for datasources reachable only via bastion. Common in enterprise.
- **`private_key` / `service_account_json` credential kinds reaching a real dialect.** The kinds are catalogued and refused by every shipped adapter (§3.4); Snowflake key-pair auth and BigQuery service accounts are what will declare them.
- **`LAKE` dialect — an object store read in place: SHIPPED.** The dialect and adapter landed in 087; round 089 added **dp-lake** (§8C): the dp-catalog registry, per-table views on connect, registry-backed introspection, engine limits, image-bundled extensions, and the `sample-lake` demo datasource reading the published `s3://datapipelines-co/sample-data/lake/<version>/` objects in place (built by [`scripts/sample-data-lake/`](../scripts/sample-data-lake/README.md) — 088). What stays future: external catalogs as a registry source (§8C.8).

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-09-10 | v2.24 | 109 §B dialect properties on the wire, the empty refusal, the LISTING probe | **§3.2 gains `properties.dialect` on reads** — non-secret keys only, filtered through the same §5.6 classification that validates `properties.jdbc` — with the per-dialect shown/hidden contract TABLE (the written contract the MCP twin shapes against). **The empty rule**: a declared dialect property with an empty/whitespace/null value is refused at register/update with `datasource.validation.property_empty` (§13.8 of the pipeline contract), never stored as `""`; bootstrap omits a field whose whole value is one `${VAR}` resolving set-but-empty (the shipped `catalog.ref:` placeholder's production posture), so the shipped defaults boot, while a literal `""` fails the boot with the key named. **§8.1's probe LISTS for LAKE**: a metadata version read proves nothing about object storage — the probe now runs one `glob()` LIST over the datasource's registered root (the declared `file://` catalog.ref, else the common directory prefix of the registered tables) through the probe's own connection, so a bucket policy allowing GET but denying `s3:ListBucket` (the T176 shape) FAILS with the engine text plus the one-line grant-ListBucket hint; with no globbable root the metadata read stays the whole proof. §3.3's `properties` row names all three namespaces. |
| 2026-09-04 | v2.19 | 068 key-provider seam | **§7.1 — the stored credential gains a leading KEY-VERSION byte** (`version ‖ nonce ‖ ciphertext ‖ tag`); the pre-versioning layout is refused, never guessed, and a one-off migration prefixed `0x01` onto every shipped row. New **§7.1.1 the key provider seam**: `KeyProvider`/`DataKey`, the three invariants, the shipped `env` provider and a pointer to the new [key-providers.md](key-providers.md) implementation guide. **§7.3 rewritten** — rotation is now lazy-safe (add a version, flip `encryption-key-current`, rows migrate on their next password write) with the one `get_byte` query that says when an old key can be retired, and an explicit statement that no rotation endpoint or CLI ships. §7.2's `password_encrypted` bullet records the layout. |
| 2026-09-04 | v2.18 | two-family demo split | **§8A.5 new** — the DuckDB read-only open mode, probed against the pinned duckdb_jdbc 1.5.5.1: the URL-param form is silently ignored, the connection-Properties form (`properties.jdbc.access_mode: READ_ONLY`) is honored (writes refused, and a read-only open of a missing file fails at connect — so loaders place the file before registration). `access_mode` is not in the DUCKDB §5.6 refusal set. Used by the trade family's `sample-trade-us` bootstrap entry. |
| 2026-09-02 | v2.17 | multi-instance round 2 (050) | **§5.7 gains the cross-instance pool-invalidation mechanism** (R1/M3): registry save/delete publishes the datasource name on Redis channel `dp:datasource-invalidated` after the row commit; every instance subscribes (reconnect-surviving container, subscribed before serving) and evicts, so the next use rebuilds from the row; the 044-F5 known window narrows to out-of-band row writes only. §5.7 also gains the replication sizing sentence (`maximumPoolSize × replicas ≤ the customer DB's connection limit`, echoed by §5.1's pointer). **§3.3 gains the normalize-on-read sentence** (R3, 011 D7/F2 + 020 F1 closed): SERVER_MANAGED keys stripped from `properties.hikari` in `DatasourceRow.toDatasource` — the second instance of the normalize-at-both-boundaries rule. |
| 2026-08-05 | v1.0 | initial draft | Initial datasources spec: entity, dialect adapters, pool config, credential encryption, driver packaging strategy |
| 2026-08-07 | v1.1 | consistency campaign | Applied [SPEC-REVIEW-2026-08 §2.9](SPEC-REVIEW-2026-08.md#29-datasourcesmd): encryption key required + fail-fast, typo fixed, master-key fallback chain deleted (KMS → ROADMAP) [D8]; `properties` becomes `hikari`/`jdbc` passthrough maps validated by a test pool build [D7/D2]; §7.2 DDL replaced by a pointer to metadata-db [D4]; `description` optional; `datasource.driver_not_loaded` renamed + added to §9, `datasource_unreachable` linked to the central catalog [D5]; §7.4 per-lease decrypt/audit corrected to per pool build; `DeleteResult`/`TestResult`/`ValidationResult` field lists; `poolFor()` thread-safety; query-timeout precedence stated once; §11 paths get `/api/v1` + `name` immutability and rename procedure |
| 2026-08-08 | v1.2 | P3 build | §9 name uniqueness made GLOBAL — includes soft-deleted rows; recreating a deleted name is rejected with `datasource.duplicate_name`, never reactivates the old row. (Row recorded retroactively 2026-08-09 — the amendment landed in commit 1b07b49 without its Change Log row.) |
| 2026-08-09 | v1.3 | P3 build (Gate C testing review) | §7.3 v1-scope note: the key-rotation *flow* is deferred to v1.1 (no v1 trigger surface); v1 ships the encryptor primitive and the registered-but-unemitted `datasource.key_rotation` audit event. |
| 2026-08-09 | v1.4 | P3 build (Gate C security + API reviews) | New **§5.6 refused property keys**: the bounded normative exception to §2's passthrough principle — class-loading / file-path / connect-time-SQL / TLS-switch keys refused in BOTH carriers (`properties.jdbc.*` and the `jdbc_url` query segment, same union), credentials refused in the URL outright, per-dialect minimum sets tabled, fail-closed adapter contract; §9 rows updated to reference it. §6.1: `save` gains required `actor` (created_by + §7.4 audit actor — v1.1 signature was unimplementable), `list` gains optional `dialect` filter, `testConnection` returns `null` for unknown names. §7.4: audit events emitted through an injected sink (no-op default), wired by the app. |
| 2026-08-09 | v1.5 | P3 build (Gate C API re-review) | Doc-sync of behavior the fix cycle added: §4.2 `DialectAdapter` gains `refusedPropertyKeys` (DS-API-12); §7.1 records the datasource-`name`-as-GCM-AAD binding and §7.3 rotation recipe now carries it through both halves (DS-API-13 — a literal reading of the old recipe would fail every tag); §7.4 states the `pool_build`/`pool_rebuild` timing + actor/cause rules, enums §15 gloss matched (DS-API-10). |
| 2026-08-09 | v1.9 | P3 build (sqlite ATTACH hardening) | §5.6: `SqliteDialectAdapter.defaultProperties` now sets `limit_attached=0` at connect (closes SQLite `ATTACH DATABASE` filesystem-access vector — an in-process engine must not let author SQL open and query any server file). `enable_load_extension=false` made explicit in `defaultProperties` (was driver-default only). Both keys added to the SQLITE §5.6 refusal set so `properties.jdbc` cannot override. |
| 2026-08-09 | v1.8 | P3 build (round-3 escalation) | §5.6 DuckDB hardening sharpened on empirical evidence: `enable_external_access=false` is the load-bearing, runtime-non-overridable lock (the two `autoload_*` toggles stay runtime-settable but are inert — no load path without external access); the five extension keys are ALSO refused in `properties.jdbc`/`jdbc_url` (properties override defaults §4.2, so an operator could otherwise re-open the RCE — refused, not merely defaulted). |
| 2026-08-09 | v1.7 | P3 build (datasources round-2 finding) | §5.6: **embedded in-process dialects** (DuckDB, SQLite run in the server JVM) must harden extension-loading at the adapter — `DuckdbDialectAdapter.defaultProperties` disables native-extension load + external autoload (`allow_community_extensions`/`autoload_known_extensions` default TRUE, reachable with no `properties.jdbc` at all → in-process RCE), non-overridable by session SQL. The datasource analogue of Staging §9.5. |
| 2026-08-09 | v1.6 | P3 build (Gate C security re-review) | §5.6 hardened: credential refusal now covers a **userinfo authority in any position** (Oracle `thin:user/pw@`, H2 `tcp://user:pw@`, not only `//`-prefixed) (DS-SEC-13); a **secret-valued-property category rule** refuses any `properties.jdbc`/URL key whose value is credential material via a suffix predicate (`*password`/`*passwd`/`*pwd`/`*secret`/`*clientkey` + named dialect secrets) since `properties.jdbc` is plaintext + `read`-visible like `jdbc_url` (DS-SEC-14); MySQL `allowPublicKeyRetrieval` (DS-SEC-16), MSSQL `keyVaultProviderClientKey`/`keyVaultProviderClientId`/`keyStorePrincipalId` (DS-SEC-14/18) added; TLS-switch refusals declared best-effort with the `sslmode` family operator-controlled. §6.3: metadata cache carries a short TTL (60s) for cross-instance coherence, negatives never cached (DS-SEC-15). |
| 2026-08-14 | v2.0 | v1.1 introspection build | New **§7A Schema Introspection**: three read-only `DatabaseMetaData` operations (tables / columns / 200-table-capped snapshot) served through the registry pool with dialect canonical type mapping, `author` scope on every surface, empty-list-for-unknown-filter rule, `datasource.not_found` for unknown names; §14 future-work line removed (shipped). |
| 2026-08-15 | v2.1 | surface restructure (part 1) | §7A: the **Schema snapshot operation is removed** (`datasources_get_schema` / `GET /datasources/{name}/schema`) — bundling columns into a table listing made responses heavy; the tables listing stays lightweight so more tables fit in one response, and columns are read per table. Tables row documents the lightweight rule. |
| 2026-08-15 | v2.2 | surface restructure (part 2) | §7A: new **Schemas operation** — the flow's entry point (`datasources_get_schemas` / `GET /datasources/{name}/schemas`): plain list of driver-reported schema names, system schemas excluded, `getCatalogs()` under MySQL's catalog routing, **empty list valid** on schemaless dialects. Flow declared: schemas → tables → columns; tables row now documents that the unfiltered listing spans schemas (pass each table's schema to columns). |
| 2026-08-15 | v2.3 | semantics via remarks | §7A: tables and columns rows gain `remarks` — the engine-stored comment from JDBC REMARKS, null-omitted on the wire when the driver/database has none. Schemas carry none (`getSchemas()` has no REMARKS). |
| 2026-08-15 | v2.4 | surface restructure (part 3) | §7A: the flow description made explicit — schemas → tables → columns, with `datasources_get_columns` reading only the tables the SQL needs (mirrors mcp-server §8.2's rewritten walkthrough, same commit). |
| 2026-08-15 | v2.5 | R4 hardening | §7A: Oracle's exclusion list corrected — `information_schema` removed (Oracle has no such schema) and the common Oracle-maintained set added (`CTXSYS`, `MDSYS`, `ORDSYS`, `DBSNMP`, `WMSYS`, `AUDSYS`, `OLAPSYS`, `XS$NULL`, `APEX_*` as a prefix entry for the versioned APEX schemas). The per-dialect lists are now marked a **floor, explicitly known-incomplete**. |
| 2026-08-15 | v2.6 | hardening round 3 (005 review fix-cycle) | §7A: post-lease connection-loss family widened (SQLTimeoutException; SQLite's null-SQLState SQLiteException classified by primary result codes BUSY/IOERR/CANTOPEN/NOTADB — never blanket null-SQLState; cause/nextException chains walked, bounded and cycle-safe); a blank CALLER schema filter now means absent (current-schema default / spanning listing, REST==MCP); blank remarks are omitted on the wire (Connector/J reports \"\" for uncommented rows); **unknown current schema + no schema argument fails tables()/columns()** with the reused `pipeline.execution.parameter_required` instead of an unfiltered merge (schemaless SQLite exempt — no schema dimension, nothing to merge; new `DialectAdapter.introspectionSchemaless`); the schemas listing is **capped at 2000 with a `truncated` flag** (`{\"schemas\": [...], \"truncated\": bool}` — was a bare array); MSSQL floor gains the ten built-in fixed-role/special schemas (dbo deliberately kept visible) and DuckDB gains `pg_catalog` — both floors; **new optional `introspection_include_schemas` allowlist** (§3.3) exempts exact lowercase names from the exclusion in all three operations (escape hatch for the `apex_*` over-exclusion; V2 column, patterns rejected at save); §9 `properties_invalid` row extended accordingly. |
| 2026-08-16 | v2.7 | hardening round 4 (007 review fix-cycle) | §3.3/§7A/§9: include-schemas save-time rejection widened from `*` alone to the SQL-LIKE vocabulary — entries carrying `%` are rejected alongside `*` (an `apex_%` entry validated, stored, and never exempted anything); `_` deliberately stays a legal name character (exact-match semantics — the documented use case exempts `APEX_REPORTING` via `apex_reporting`). |
| 2026-08-16 | v2.8 | hardening round 4 (007 review fix-cycle) | §3.3/§7A: the allowlist lowercase invariant moved from the REST bind (one HTTP bind among possible writers) to the **registry save boundary** — the single place every write path crosses — with the **repository read boundary normalizing too**, so a row whose allowlist landed by restore or a manual JSONB edit can never sit silently inert (matching lowercases the reported schema and compares stored entries verbatim). |
| 2026-08-16 | v2.9 | hardening round 4 (007 review fix-cycle) | §7A Tables row: the unknown-current-schema guard is REMOVED from tables() (scoped to columns(), where the cannot-merge hazard is real). A tables listing carries each row own schema and cannot merge; when the current schema IS known the guard passed while the unfiltered listing still spanned every schema/catalog, so it prevented nothing it claimed to prevent and regressed previously-working unfiltered listings on drivers that throw SQLFeatureNotSupportedException from getSchema()/getCatalog(). tables() no longer consults the current schema at all. |
| 2026-08-16 | v2.10 | hardening round 5 (008 review fix-cycle) | §7A: every `SQLException` the current-schema read can produce is classified in ONE place (the lease boundary's own connection-family classifier, extended — no second fork): feature-unsupported (typed exception or SQLState `0A000`) reads as "driver reports none"; connection loss (now including h2's closed-connection codes 90007/90098/90121 and the DuckDB/SQLite closed-connection lifecycle messages — both live-pinned per driver in `EmbeddedDialectBehaviorTest`) is the 502 `datasource_unreachable`; anything left (pgjdbc's `getSchema()` is query-backed — statement cancel `57014`, permission errors) is the catalogued `parameter_required` with the driver exception as cause. Round 4's narrowed catch let all three shapes escape as raw 500 / JSON-RPC -32603. |
| 2026-08-16 | v2.11 | hardening round 5 (008 review fix-cycle) | §3.3/§7A: the ONE allowlist normalization rule completes to trim → lowercase → **drop blank-after-trim entries** → **deduplicate** (first-seen order), at both boundaries. A restored/hand-edited `[" "]` row used to read back as `[""]` — projected to REST and MCP, exempting nothing, and 400ing an unmodified PUT round-trip (the validator rejects blanks); blanks and duplicates are now dropped at normalization, so what a GET projects always survives an unmodified re-save. |
| 2026-08-16 | v2.12 | hardening round 5 (008 review fix-cycle) | §3.3: the ONE rule has ONE implementation and every path crosses it — the REST bind calls the shared `normalizeIncludeSchemas` (its re-inlined trim+lowercase is gone), and the registry's `validate()` (the dry-run path) validates the NORMALIZED form, agreeing with `save()` by construction instead of checking raw input that save never persists. The helper short-circuits empty and already-normalized input (it runs per row on the uncached `list()` read path). |
| 2026-08-16 | v2.13 | hardening round 5 (008 review fix-cycle) | §3.3/§7A/§9: the wildcard DENYLIST (`*` R3, `%` R4) is replaced by an ALLOWLIST of legal schema-identifier characters across the supported dialects — letters, digits, `_`, `$`, `#`, lowercase. The complement of "can match a real schema name" closes the whole inert-entry class at once: `?`, glob ranges `[a-z]`, pasted quoted identifiers, and qualified `db.schema` entries stored and silently exempted nothing before; now they are rejected with the entry named and the accepted alphabet stated. |
| 2026-08-28 | v2.15 | workspaces surfaces slice | §3: the write vocabulary gains `global` (admin-only) / `workspace` (accessible-to-caller, default ACTIVE) / `readonly`; the response gains additive `workspace` + `readonly`. **Visibility (§5.3-of-design) made normative here**: listing/by-name see ACTIVE-bound + global, enforced in repository SQL (`findAllVisible`/`findVisibleByName`), other-workspace rows are not-found, names stay a flat global namespace (cross-workspace create collisions are `duplicate_name`, by design). §5.7's flag is now writable through the D8-gated registry save path (pool rebuilt on every flag write); the "row-level state in this slice" note is history. |
| 2026-08-27 | v2.14 | workspaces readonly slice | New **§5.7 Readonly datasources** (workspaces design 2026-08-16 §6, D6/D10): the `is_readonly` V4 column's semantics — the three forbidden write shapes, the save-time code (`pipeline.validation.datasource_readonly`, contract §12.5), the live-registry executor backstop (`pipeline.node.datasource_readonly`, contract §13.4, past the §6.3 cache), the Hikari `readOnly` pool flag as defense in depth, and the normative SELECT-only-user deployment guidance. §5.6: `readOnly` joins the server-managed refusal set — refused in BOTH directions and both carriers. Flag writes/payload fields explicitly deferred to the surfaces slice. |
| 2026-08-28 | v2.15 | sample data, slice A | **§8A new:** bootstrap registration — the `datapipelines.bootstrap.datasources-file` mechanism (§8A.1 file shape incl. the required-and-true `global` flag and `readonly`, §8A.2 `${ENV_VAR}` resolution against the process environment, §8A.3 create-if-absent / never-update / full-§9-validation-per-entry / fail-fast, §8A.4 the xerial `open_mode: "1"` read-only key verified against pinned 3.49.1.0). `is_readonly` is now written on INSERT (from the entity, which the REST bind still never sets — flag writes over the API remain deferred to the surfaces slice); UPDATE still never touches it |
| 2026-09-02 | v2.16 | 020 fix-cycle (044) — the backstop goes fail-closed | §5.7: the executor backstop's **null semantics made normative** — no live row refuses as `pipeline.node.datasource_not_found` (the D10 soft-delete channel), a metadata-DB failure during the live read refuses as `pipeline.execution.aborted` naming the METADATA database (never the healthy target), both replacing 020's "null = no signal" fail-open. **Layer 1 reads live** (`getVisibleLive`/`getLive`, past the §6.3 cache — 020 F4's both-directions stale-save window closed); **layer 2's read is flag-only** (`isReadonlyLive`, one indexed `SELECT is_readonly` — no ciphertext, no properties parse; 020 F7) and a readonly write-back target is refused at CONNECT, before the source query (020 F9); **layer 3's pool-staleness window documented** (no TTL; row-level flips leave the pre-flip pool — M3's within-one-JVM twin, fix deferred to M3's owner decision; 020 F5). §6.1: the interface sketch gains the three live reads; `getLive`/`isReadonlyLive` are abstract (020 F6 — a cached default was the hole). §6.1's registry KDoc wiring example corrected to the `describe`/`DatasourceFacts` SAM (020 F10 — the old `dialectOf` example no longer compiled, verified). |
| 2026-09-03 | v2.17 | 061 — datasource credentials and references | **§8A.3 gains rule 3** (T84): a bootstrap entry whose FILE credential differs from the STORED one is reconciled by connection-testing both — stored-works keeps the row byte-untouched (rule 1 intact), stored-fails-and-file-works replaces the credential ALONE with a WARN, neither-works and undecryptable both leave the row and log ERROR naming the env key / the encryption key, and a soft-deleted row is never touched. Startup never fails on it. **New §8.1B** (T84): the last connection test's outcome is stored (`last_test_at`/`last_test_ok`/`last_test_message`, V9) and surfaced as the additive `last_test` field (§3.2) and a datasources-screen column — because listing never connects, and on 2026-09-02 the screen said "fine" while every execution failed at CONNECT. That write touches the three columns only and does NOT move `updated_at` (the one documented exception to metadata-db §2), which is what keeps rule 1's byte-untouched guarantee checkable. **§6.2 rewritten** (T79): the delete guard reads the ANY-VERSION reference scan, not the current-version one — a released v1 pinning a datasource that v2 dropped is a live reference (immutable, executable by explicit version) and used to be invisible, so the delete succeeded and v1's next execution failed at connect; the 409 now carries the referencing nodes with their pipeline versions, the way `template.in_use` does. |
| 2026-09-07 | v2.18 | 087 connector seams | **New §3.4 credential kinds** — `credential: {kind, username?, secret?}` with `kind ∈ password \| token \| private_key \| service_account_json \| none`; the legacy top-level `username`/`password` pair stays accepted and means `kind: password` (§12.1), and a payload carrying both is refused. Which kinds a dialect accepts is its adapter's declaration (`supportedCredentialKinds`), enforced fail-closed; `private_key`/`service_account_json` are catalogued for the reference targets and refused by every shipped adapter. §3.1/§3.2/§3.3 updated; `password_set` is now DERIVED from the kind (V13's CHECK makes `kind = 'none'` ⟺ no stored ciphertext); §8A.1's dummy SQLite password is gone. **§4.2 gains `NamespaceShape`** (`labels`, `levels`, `innermostArrivesInCatalog`) replacing the boolean `schemaArrivesInCatalog`, with the four reference targets' shapes written in as the contract; §7A's listings and filters speak NAMESPACES — `entries: [{namespace, label}]` beside the legacy `schemas`, `namespace` beside `schema` on every table row and filter, dotted `introspection_include_schemas` entries. The catalog argument reaching `getTables`/`getColumns` closes a MEASURED merge (two ATTACHed DuckDB catalogs' same-named schemas listed as one, and an unqualified `getColumns` returned both tables' columns). **New §4.2A**: `connectionInit` wired to HikariCP's previously-unreferenced `connectionInitSql`, and a third reserved `properties` namespace `dialect.*` — TYPED and adapter-validated, refused wholesale by default. `connectionInitSql` joins the §5.6 server-managed set (DS-SEC-22); §5.6 also gains named secret-valued keys the suffix predicate cannot catch (`OAuthPvtKey`, `Auth_AccessToken`, …) and the one-line credential-carrier rule. **New `LAKE` dialect** (§4.1): object storage read in place, DuckDB underneath, without the embedded adapter's `enable_external_access` lock — a distinct dialect, not a mode, because a mode would make the §5.6 refusal set a function of row data. §7B: write-back identifiers quote in the TARGET dialect's vocabulary. |
| 2026-09-10 | v2.23 | 109 §A lake view isolation | **§8C.2 rewritten for per-table isolation**: a LAKE pool's views no longer ride one joined `connectionInitSql` — the driver DataSource is wrapped (`LakeViewApplyingDataSource`), the adapter init and shared prelude stay strict, and each table's view is applied independently: a failing view is recorded on its registry row (`lake_tables.last_error` / `last_error_at`, V22) and skipped, so one broken table no longer takes every table down; emission-boundary refusals (3+-segment namespaces, bad locations) are captured per table instead of failing the pool build. Recording is transition-only; a success clears the row. A node referencing a broken table fails at CONNECT with `datasource.lake.table_unavailable` (502, `details.table` + `details.last_error`); the detail tree and `GET …/lake-tables` show the recorded error. **§8C.1 gains the registration pre-flight**: `register` and `importTables` prove a candidate table readable on a scratch connection (view + one-row scan) before storing, refusing with `datasource.validation.lake_table_unreadable` (400, bounded engine text); import pre-flights only not-yet-registered triples, keeping bootstrap re-runs free. |
| 2026-09-08 | v2.21 | 094 pool settings, delete, retirement | **§5 gains the eight tunable pool keys** with units, effective defaults, the layer each default comes from (`dialect` → `server` → HikariCP's own, read from a fresh `HikariConfig` rather than transcribed) and their FLOORS — refused at save with `datasource.validation.properties_invalid`, because `HikariConfig.validateNumerics()` enforces almost all of them by logging a WARN and OVERWRITING, so the row and the screen would otherwise show numbers the pool never uses. Passthrough is unchanged: the eight are what a PERSON is offered, not an allowlist. A field left at its default is not persisted. `GET /datasources/{name}` and `datasources_get` gain a `pool` object (value + unit + source per key). **§5.2 replaces close-at-once with RETIRE-then-close**: a save or delete removes the pool from the map and soft-evicts it (`minimumIdle = 0`, then `softEvictConnections()` — idle close now, in-use close on return, never mid-statement), and a per-instance reaper closes it once drained or at `datapipelines.datasources.retire-ceiling-seconds` ([Configuration §3.26](configuration.md#326-datasource-pools), default = the node query timeout + 30 s) with one WARN and `datapipelines.datasource.pool.hard_closed`. Retiring pools live in a queue, not a name-keyed map. **Reconcile on (re)subscribe** replaces any notion of a retry queue for missed §5.7 invalidation messages: each instance compares its live pools' row versions against the rows whenever the channel (re)subscribes. **§6.2**: the delete guard's scan is now what the UI's Delete dialog ASKS FIRST — in-use renders the referencing nodes and offers no button; unused gets a confirm that names the datasource; the POST re-runs the guard regardless. |
| 2026-09-08 | v2.20 | 089 dp-lake (§G docs) | **New §8C dp-lake** — the shipped lake, documented to the tree: **§8C.1 dp-catalog** (the `lake_tables` registry; `datasource_id` holds the datasource NAME; the 1–9-segment namespace grammar, the `s3://`/`file://`-only total location refusal, the REST surface of rest-api §9.8 and the three `lake_tables_*` MCP tools, the read-only detail tree, pool eviction + the §5.7 invalidation on every write), **§8C.2 the per-table views on connect** (the catalog/schema mapping for one- and two-segment namespaces, the refusal of deeper ones, unconditional `hive_partitioning = true` for Parquet, and the **search-path rule**: `SET search_path` only when ALL tables share exactly ONE namespace — the demo's bare-name choice, documented here and in the SKILL), **§8C.3 registry-backed introspection** (namespaces as schemas, registry rows reported as VIEW with format in remarks, columns as a zero-row select over the view through the DuckDB mapper, nested → STRING + warning, 60 s cache), **§8C.4 the engine limits** (`dialect.memory_limit` defaulting to 25 % of container memory clamped to 64 MiB – 4 GiB, `dialect.threads`, `dialect.temp_directory`, always-on `preserve_insertion_order = false`), **§8C.5 the image-bundled extensions** (`httpfs`/`aws`/`iceberg`/`avro` at `/opt/duckdb/extensions`; LOAD-only with the directory present — no egress — INSTALL+LOAD without it; the iceberg extension loads whenever the registry holds an iceberg table, under any catalog kind), **§8C.6 credentials and addressing** (`none` = `credential_chain`, `password` = KEY_ID/SECRET; `region`/`endpoint`/`url_style`), **§8C.7 the measured Iceberg location rule** (DuckDB 1.5.5.1 cannot `iceberg_scan` a pyiceberg table by its ROOT — register the current metadata FILE; this contradicts the design record, and the tree won), and **§8C.8 the not-in-this-round list** (external catalogs as a registry source, writes, scheduler, dashboards, nested types, Athena). §8A.1 documents the LAKE bootstrap seed (`tables:` / `import_manifest:` / `namespace:` / `only_tables:` and the four parse-time refusals); §4.1's LAKE row gains the §8C pointer and its typed-config reference corrected to §4.2A; §4.2's adapter list gains `LakeDialectAdapter`; §14's LAKE bullet rewritten as shipped. |
| 2026-09-09 | v2.22 | 107 agent probes | **New §7C catalog table statistics** (`datasources_get_table_stats`, MCP-only) — one table's row estimate, indexes and per-column bounds always from the engine's OWN catalog, never a scan: the per-dialect `TableStatsPlan` recipes tabled (`pg_class`+`pg_stats`, `information_schema`, `duckdb_tables()`+`duckdb_constraints()`/`duckdb_indexes()`, post-ANALYZE `sqlite_stat1`, `all_tables`, `sys.partitions`, LAKE `parquet_metadata` footer reads with numeric-aware min/max), `stats_source` naming the catalog or `none`; the statement timeout clamps to 10 s; an unknown table is empty stats, an unknown datasource `datasource.not_found`; a lake partition column reports as the `{kind: "partition"}` pseudo-index and Iceberg reports `none` (documented gap). **New §7D the SQL probe** (`sql_probe`, MCP-only) — ONE classified SELECT/WITH (the conservative denylist; false positives err toward refusal, and the side-effecting-function gap is documented), EXPLAIN captured BEFORE the query so the plan survives the timeout it explains (per-dialect plan table; ORACLE/MSSQL plan-less; DuckDB's `Scanning Files: x/y` → `partitions_scanned`/`partitions_total`, an absent marker an honest null, never y/y), named typed parameters through the pipeline's own binder, `tempdb` refused, the `sql` argument audited as `sql_sha256` + `sql_length`, never verbatim. §7A's "read-only by construction" rule amended to scope itself to the three metadata operations — the new siblings execute statements but never write. |
